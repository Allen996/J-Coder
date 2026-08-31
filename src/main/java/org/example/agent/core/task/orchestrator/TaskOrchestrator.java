package org.example.agent.core.task.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.dag.DagGraph;
import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.dag.DagNodeState;
import org.example.agent.core.task.dag.DagPlanStatus;
import org.example.agent.core.task.dag.DagState;
import org.example.agent.core.task.dag.DagStateRepository;
import org.example.agent.core.task.subagent.SubAgentResult;
import org.example.agent.core.task.subagent.SubAgentRunner;
import org.example.agent.core.task.subagent.SubAgentStatus;
import org.example.agent.core.task.subagent.SubAgentTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.example.agent.core.task.TaskSystemConfig;

/**
 * 任务编排器（阶段 2 重写）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>管理 in-memory 的 active plan（{@code activeGraph} + {@code activeState}）</li>
 *   <li>状态机推进：create_plan / append_subtask / dispatch_subtask / poll_subagent_results</li>
 *   <li>DAG 状态在内存（DagState）+ 独立落盘 dag-state.json（M.1）</li>
 *   <li>并发：主 loop 单线程消费结果（M.2 W）</li>
 *   <li>异步派发：通过 Spring TaskExecutor（{@link TaskSystemConfig#taskAsyncExecutor}）异步 submit SubAgent</li>
 * </ul>
 *
 * <p>阶段 2 不再有 VERIFY/FIX/SKIP 级联 —— 上游失败由主 Agent 决定是否派下游。
 */
@Slf4j
@Component
public class TaskOrchestrator {

    private final DagStateRepository repository;
    private final SubAgentRunner subAgentRunner;
    /** 异步派发 SubAgent 的线程池（来自 {@link TaskSystemConfig#taskAsyncExecutor()}）。 */
    private final TaskExecutor taskExecutor;
    /** 阶段 4：当前 session 的最近 Checkpoint id（给 SubAgent.parentCheckpointId 留引用）。 */
    private volatile String currentCheckpointId = "";
    /** 阶段 4：CheckpointService 注入 —— 给 CheckpointTool 路径同步序号。 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private org.example.agent.core.task.CheckpointService checkpointService;

    /** 主 Agent 当前持有的 DAG 静态结构。null = 无 active plan。 */
    private volatile DagGraph activeGraph;
    /** 主 Agent 当前持有的 DAG 运行时状态（与 activeGraph 同步切换）。 */
    private volatile DagState activeState;
    /** 异步派发的 SubAgent Future —— 主 loop poll 时按 taskId 取结果。 */
    private final Map<String, Future<SubAgentResult>> pending = new ConcurrentHashMap<>();
    /** 主 session 消息存储 —— 用于写 [meta] subagent_run 到主 worklog（K.1）。可为 null（测试桩）。 */
    private final SessionMessageStore sessionStore;

    @Autowired
    public TaskOrchestrator(DagStateRepository repository,
                            @Lazy SubAgentRunner subAgentRunner,
                            @Qualifier(TaskSystemConfig.TASK_ASYNC_EXECUTOR_BEAN) TaskExecutor taskExecutor,
                            @Lazy SessionMessageStore sessionStore) {
        this.repository = repository;
        this.subAgentRunner = subAgentRunner;
        this.taskExecutor = taskExecutor;
        this.sessionStore = sessionStore;
    }

    // ==================== Plan 生命周期 ====================

    /**
     * 创建 plan（由 create_plan 工具调用）。初始化 DagGraph + DagState,落盘 plan.json,
     * 不立即派发任何 SubAgent —— 主 Agent 后续通过 dispatch_subtask 派发。
     *
     * @param sessionId  主 session id（用于文件路径 + DagGraph.sessionId）
     * @param specs      LLM 给出的节点列表（taskId/title/description/dependsOn/expectedOutput）
     */
    public DagGraph createPlan(String sessionId, List<DagNode> specs) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        String planId = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        String goal = specs == null || specs.isEmpty() ? "(no goal)" : specs.get(0).getTitle();

        DagGraph graph = DagGraph.empty(planId, goal, sessionId);
        if (specs != null && !specs.isEmpty()) {
            graph = graph.withNodes(specs);
        }

        repository.savePlan(graph);
        this.activeGraph = graph;
        this.activeState = DagState.empty(planId, sessionId);
        repository.saveDagState(activeState);
        pending.clear();
        log.info("plan created: {} session={} nodes={}", planId, sessionId, graph.size());
        return graph;
    }

    /**
     * 追加一个新节点到现有 plan。校验依赖 + taskId 唯一 + 写入 dag-state.json 的 runtime。
     */
    public DagNode appendSubtask(String taskId, String title, String description,
                                 List<String> dependsOn, String expectedOutput) {
        DagGraph g = requireActiveGraph();
        DagNode node = DagNode.pending(taskId, title, description, dependsOn, expectedOutput);
        DagGraph next = g.withNodeAdded(node);
        repository.savePlan(next);
        this.activeGraph = next;
        // dag-state.runtime 也加进去（保持一致）
        DagState nextState = activeState.withNodeUpdated(node);
        this.activeState = nextState;
        repository.saveDagState(nextState);
        log.info("subtask appended: taskId={} session={}", taskId, g.getSessionId());
        return node;
    }

    /**
     * 派发一个 SubAgent。同步立即 return（status=running），主 loop 在下一轮推理前
     * 调 {@link #pollPendingSubagents()} 一次性 await 所有。
     *
     * @return 给主 Agent 看的"立即返回"结果（status=running + 提示 await 时机）
     */
    public DispatchResult dispatchSubtask(String taskId, String title, String description,
                                          String expectedOutput, List<String> contextFiles,
                                          long timeoutMs, String parentCheckpointId) {
        DagGraph g = requireActiveGraph();
        DagState s = requireActiveState();
        DagNode staticNode = g.get(taskId);
        if (staticNode == null) {
            throw new IllegalArgumentException("taskId not in graph: " + taskId
                    + " —— dispatch_subtask 只能用于 append_subtask 或 create_plan 已声明的节点");
        }
        // 节点运行时状态:优先 activeState(包含 attempts/state),无则用静态(初始 PENDING/attempts=0)
        DagNode runtimeNode = s.get(taskId);
        DagNode current = runtimeNode != null ? runtimeNode : staticNode;
        // 校验依赖已 COMPLETED
        for (String dep : current.dependencies()) {
            DagNode depNode = s.get(dep);
            if (depNode == null || depNode.getState() != DagNodeState.COMPLETED) {
                throw new IllegalStateException("taskId=" + taskId + " dependency " + dep
                        + " not COMPLETED (state=" + (depNode == null ? "missing" : depNode.getState()) + ")");
            }
        }
        // 校验不是 COMPLETED 终态 —— FAILED / TIMEOUT 允许重试（L.4 决定）
        if (current.getState() == DagNodeState.COMPLETED) {
            throw new IllegalStateException("taskId=" + taskId + " already COMPLETED; "
                    + "if you need to redo, use append_subtask to add a new node");
        }

        DagNode started = current
                .withState(DagNodeState.IN_PROGRESS)
                .withStartedAt(Instant.now())
                .withAttempts(current.getAttempts() + 1)
                .withLastResult(null);
        updateNode(started);

        SubAgentTask sub = new SubAgentTask(
                taskId, title, description, expectedOutput,
                contextFiles == null ? List.of() : contextFiles,
                g.getSessionId(),
                resolveParentCheckpointId(parentCheckpointId),
                timeoutMs);

        // 异步 submit —— Spring TaskExecutor 只有 execute(Runnable),需要 FutureTask 包装 Callable
        FutureTask<SubAgentResult> ft = new FutureTask<>((Callable<SubAgentResult>) () -> subAgentRunner.run(sub));
        taskExecutor.execute(ft);
        pending.put(taskId, ft);

        log.info("dispatch_subtask: taskId={} session={} async submitted (timeout={}ms, attempt={})",
                taskId, g.getSessionId(), timeoutMs, started.getAttempts());
        return new DispatchResult(taskId, "running",
                "SubAgent " + taskId + " submitted; will be awaited at next reasoning turn");
    }

    /**
     * 主 loop 在每轮推理前调用。await 所有 pending SubAgent,把结果汇入 DagState,
     * 然后清空 pending map。返回本轮汇总的 finalReport（用于给主 Agent 的工作上下文）。
     */
    public PollSummary pollPendingSubagents() {
        if (pending.isEmpty()) {
            return new PollSummary(0, List.of());
        }
        Map<String, Future<SubAgentResult>> snapshot = new HashMap<>(pending);
        List<String> reports = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, Future<SubAgentResult>> e : snapshot.entrySet()) {
            String taskId = e.getKey();
            Future<SubAgentResult> f = e.getValue();
            SubAgentResult result;
            try {
                // SubAgentRunner 内部已经做了 Future.get(timeout),这里应该立即返回或抛 CancellationException
                result = f.get(50, TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                log.warn("pollPendingSubagents: taskId={} not finished: {}", taskId, ex.getMessage());
                continue; // 还在跑,本轮不收
            }
            pending.remove(taskId);
            n++;
            applyResult(taskId, result, reports);
        }
        // 批量落盘（M.3）
        if (activeState != null) {
            repository.saveDagState(activeState);
        }
        return new PollSummary(n, reports);
    }

    private void applyResult(String taskId, SubAgentResult result, List<String> reports) {
        DagNode existing = activeState.get(taskId);
        if (existing == null) {
            log.warn("applyResult: taskId={} missing from state", taskId);
            return;
        }
        DagNodeState newState = switch (result.status()) {
            case COMPLETED -> DagNodeState.COMPLETED;
            case FAILED -> DagNodeState.FAILED;
            case TIMEOUT -> DagNodeState.TIMEOUT;
        };
        DagNode.NodeResult nr = DagNode.NodeResult.builder()
                .status(result.status().name())
                .report(result.report())
                .reason(result.reason())
                .artifacts(result.artifacts())
                .durationMs(result.durationMs())
                .build();
        DagNode updated = existing
                .withState(newState)
                .withCompletedAt(Instant.now())
                .withLastResult(nr);
        updateNode(updated);
        reports.add(formatReport(taskId, result));

        // 阶段 3（K.1 + K.2）：主 session worklog 写入 [meta] subagent_run 嵌套指针
        writeSubAgentRunMeta(taskId, result);

        log.info("SubAgent result applied: taskId={} status={} duration={}ms",
                taskId, result.status(), result.durationMs());
    }

    /**
     * 阶段 3 钩入：主 session worklog 写入 [meta] subagent_run 嵌套指针(K.1 后者)。
     * 内容含 taskId / sessionPath / durationMs / status / reportPreview(头 200 字)。
     * best-effort:sessionStore 为 null 时跳过,异常只 warn。
     */
    private void writeSubAgentRunMeta(String taskId, SubAgentResult result) {
        if (sessionStore == null || activeGraph == null) return;
        String parentSid = activeGraph.getSessionId();
        if (parentSid == null || parentSid.isBlank()) return;
        try {
            String report = result.report() == null ? "" : result.report();
            String preview = report.length() > 200 ? report.substring(0, 200) + "..." : report;
            String content = "taskId=" + taskId
                    + " status=" + result.status().name()
                    + " durationMs=" + result.durationMs()
                    + " sessionPath=" + result.sessionPath()
                    + " reportPreview=" + preview.replace("\n", "\\n");
            sessionStore.addMeta(parentSid, content);
            log.debug("writeSubAgentRunMeta: session={} taskId={} status={}",
                    parentSid, taskId, result.status());
        } catch (RuntimeException ex) {
            log.warn("writeSubAgentRunMeta failed: session={} taskId={}: {}",
                    activeGraph.getSessionId(), taskId, ex.getMessage());
        }
    }

    private static String formatReport(String taskId, SubAgentResult r) {
        if (r.status() == SubAgentStatus.COMPLETED) {
            return "[SubAgent " + taskId + " COMPLETED in " + r.durationMs() + "ms]\n" + r.report();
        }
        return "[SubAgent " + taskId + " " + r.status() + " in " + r.durationMs() + "ms] "
                + (r.reason().isBlank() ? "" : "reason=" + r.reason() + "\n")
                + (r.report().isBlank() ? "(no report)" : r.report());
    }

    /** 内部：更新节点 + 写 dag-state。 */
    private void updateNode(DagNode node) {
        this.activeState = activeState.withNodeUpdated(node);
    }

    // ==================== 查询 / 控制 ====================

    public Optional<DagGraph> activeGraph() {
        return Optional.ofNullable(activeGraph);
    }

    /**
     * 阶段 4:给 CheckpointTool / InspectSubagentTool 用 —— 当前 in-memory DagState。
     * 与 {@link #activeGraph()} 配对:graph 持有静态结构,state 持有运行时状态。
     */
    public Optional<DagState> activeState() {
        return Optional.ofNullable(activeState);
    }

    /**
     * 阶段 4:返回当前 session 最近一次 Checkpoint id(主 Agent 调 checkpoint 工具后写入)。
     * 用于 dispatch_subtask 时填充 SubAgent.parentCheckpointId(便于将来追溯/撤销)。
     */
    public String getCurrentCheckpointId() {
        return currentCheckpointId == null ? "" : currentCheckpointId;
    }

    /** 阶段 4:CheckpointService 调这个方法登记新 id。 */
    public void setCurrentCheckpointId(String id) {
        this.currentCheckpointId = id == null ? "" : id;
    }

    /** 阶段 4:暴露 DagStateRepository 给 CheckpointTool 读 dag-state.json。 */
    public DagStateRepository getDagStateRepository() {
        return repository;
    }

    /**
     * 阶段 4:把 explicit parentCheckpointId 与 currentCheckpointId 合并。
     * callers 在 dispatch_subtask 没传 parentCheckpointId 时,自动继承最近一次 Checkpoint id。
     */
    private String resolveParentCheckpointId(String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return currentCheckpointId == null ? "" : currentCheckpointId;
    }

    // 阶段 5:activePlan() 删除 —— 不再有旧 TaskPlan 兼容视图。CLI 用 activeGraph() / activeState()。

    /** 主 Agent 跑完所有 SubAgent 后,plan 进入终态。 */
    public boolean allTerminal() {
        if (activeState == null || activeState.size() == 0) return false;
        for (DagNode n : activeState.getRuntime().values()) {
            if (!n.isTerminal()) return false;
        }
        return true;
    }

    /**
     * 阶段 2 stub:旧 runActivePlan() 串行驱动已被异步派发取代。
     * 主 Agent 每轮推理前会调 {@link #pollPendingSubagents()} await 所有 pending SubAgent。
     * 本方法保留仅为兼容旧 CLI 调用（no-op,不影响新逻辑）。
     */
    public void runActivePlan() {
        if (activeGraph == null) return;
        // 可选:本轮收一下 pending（让 CLI 派发的 sync 调用也能等结果）
        pollPendingSubagents();
    }

    public void pauseActivePlan() {
        if (activeGraph == null) return;
        log.info("plan {} paused (note: pause does not interrupt running SubAgents)", activeGraph.getPlanId());
    }

    public void resumeActivePlan() {
        if (activeGraph == null) return;
        log.info("plan {} resumed", activeGraph.getPlanId());
    }

    public void abandonActivePlan(String reason) {
        if (activeGraph == null) return;
        DagState next = activeState.withStatus(DagPlanStatus.ABANDONED);
        this.activeState = next;
        repository.saveDagState(next);
        log.info("plan {} abandoned: {}", activeGraph.getPlanId(), reason);
    }

    /**
     * 给 CLI 用的查询接口 —— 与旧 queryPlanAsText(planId) 兼容。
     */
    public String queryPlanAsText(String planId) {
        if (activeGraph == null || !activeGraph.getPlanId().equals(planId)) {
            return "(no active plan)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Plan ").append(activeGraph.getPlanId())
                .append(" · status=").append(activeState.getStatus())
                .append(" · session=").append(activeGraph.getSessionId()).append("\n");
        sb.append("Goal: ").append(activeGraph.getGoal()).append("\n\n");

        int done = 0;
        for (DagNode node : activeGraph.getNodes().values()) {
            // 状态优先从 activeState.runtime 拿,缺省 PENDING
            DagNode stateful = activeState.get(node.getTaskId());
            DagNodeState st = stateful != null ? stateful.getState() : DagNodeState.PENDING;
            String mark = switch (st) {
                case COMPLETED -> "✓ ";
                case IN_PROGRESS -> "⋯";
                case FAILED -> "✗ ";
                case TIMEOUT -> "⏱ ";
                case PENDING -> "· ";
            };
            if (st == DagNodeState.COMPLETED) done++;
            sb.append(String.format("%s [%-11s] %s  %s%n",
                    mark, st, node.getTaskId(), node.getTitle()));
            if ((st == DagNodeState.FAILED || st == DagNodeState.TIMEOUT)
                    && stateful != null && stateful.getLastResult() != null
                    && !stateful.getLastResult().getReason().isBlank()) {
                sb.append("    reason: ").append(stateful.getLastResult().getReason()).append("\n");
            }
        }
        sb.append("\nprogress: ").append(done).append("/").append(activeGraph.size()).append("\n");
        return sb.toString();
    }

    /**
     * 给旧 CLI / TaskProgressRenderer 用的兼容视图：返回旧 TaskPlan 对象。
     * 内部把 DagGraph + DagState 投影成 TaskPlan + SubTask 列表。
     */
    private DagGraph requireActiveGraph() {
        if (activeGraph == null) {
            throw new IllegalStateException("no active plan —— call create_plan first");
        }
        return activeGraph;
    }

    private DagState requireActiveState() {
        if (activeState == null) {
            throw new IllegalStateException("no active dag state —— call create_plan first");
        }
        return activeState;
    }

    // ==================== Records ====================

    /** {@link #dispatchSubtask} 的立即返回结果。 */
    public record DispatchResult(String taskId, String status, String note) { }

    /** {@link #pollPendingSubagents} 的批量汇总。 */
    public record PollSummary(int collected, List<String> reports) { }
}
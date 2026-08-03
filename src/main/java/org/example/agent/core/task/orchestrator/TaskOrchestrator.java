package org.example.agent.core.task.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.memory.MemoryIndexSynchronizer;
import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.PlanEdge;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskSpec;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.event.PlanFinishedEvent;
import org.example.agent.core.task.event.SubTaskCompletedEvent;
import org.example.agent.core.task.event.SubTaskFailedEvent;
import org.example.agent.core.task.event.SubTaskStartedEvent;
import org.example.agent.core.task.event.TaskEventPublisher;
import org.example.agent.core.task.event.TaskPlanCreatedEvent;
import org.example.agent.core.task.event.VerifyFailedEvent;
import org.example.agent.core.task.event.VerifyPassedEvent;
import org.example.agent.core.task.event.VerifyStartedEvent;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.example.agent.core.task.scheduler.TaskScheduler;
import org.example.agent.core.task.scheduler.TaskSchedulerException;
import org.example.agent.core.task.verify.VerifyCommandTemplate;
import org.example.agent.core.task.verify.VerifyResult;
import org.example.agent.core.task.verify.VerifyRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务编排器（part5 §8.8 / §8.7）。
 *
 * <p>职责：
 * <ul>
 *   <li>管理 in-memory 的 active plan（{@code activePlan} + {@code activeSubtasks}）</li>
 *   <li>状态机推进：create_plan / start_subtask / complete_subtask / fail_subtask / skip_subtask</li>
 *   <li>强制 VERIFY 收尾：SubTask COMPLETED 时若 type=VERIFY → 跑 VerifyRunner → 标记 VERIFIED 或触发 FIX</li>
 *   <li>FIX 局部插入：VERIFY 失败时把 FIX 插到 VERIFY 之前，局部改图</li>
 *   <li>checkpoint 自动追加：每个 SubTask 完成后由 orchestrator 写一条自动 checkpoint</li>
 *   <li>并发安全：volatile 引用 + 同步块保证字段一致；runActivePlan 串行</li>
 * </ul>
 *
 * <p>并发模型：v1 串行单线程（part5 §8.4 / §8.10），没有"plan 后台跑+同时答问"。
 * 用户中途插入时由外部（REPL）调 {@link #pauseActivePlan()}。
 */
@Slf4j
@Component
public class TaskOrchestrator {

    private final TaskPlanRepository repository;
    private final TaskScheduler scheduler;
    private final TaskEventPublisher publisher;
    private final VerifyRunner verifyRunner;
    private final SubTaskExecutor executor;
    private final TaskOrchestratorConfig config;
    private final MemoryIndexSynchronizer indexSync;

    private volatile TaskPlan activePlan;
    private volatile Map<String, SubTask> activeSubtasks = new HashMap<>();

    public TaskOrchestrator(TaskPlanRepository repository,
                            TaskScheduler scheduler,
                            TaskEventPublisher publisher,
                            VerifyRunner verifyRunner,
                            @Lazy SubTaskExecutor executor,
                            TaskOrchestratorConfig config,
                            @Lazy MemoryIndexSynchronizer indexSync) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.publisher = publisher;
        this.verifyRunner = verifyRunner;
        this.executor = executor;
        this.config = config == null
                ? TaskOrchestratorConfig.builder().build()
                : config;
        this.indexSync = indexSync;
    }

    // ==================== Plan 生命周期 ====================

    /**
     * 创建 plan（由 create_plan 工具调用）。
     *
     * <p>行为：分配 planId、校验 DAG、补默认 VERIFY（若需要）、落盘全部 SubTask 文件、
     * 设为 active、emit TaskPlanCreatedEvent。
     *
     * @return 落盘后的 TaskPlan
     */
    public TaskPlan createPlan(String goal, List<SubTaskSpec> specs, String sessionId) {
        String planId = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        TaskPlan plan = scheduler.buildPlan(planId, goal, sessionId, specs, true);
        repository.savePlan(plan);

        Map<String, SubTask> subs = new LinkedHashMap<>();
        for (int i = 0; i < plan.getSubtaskIds().size(); i++) {
            String taskId = plan.getSubtaskIds().get(i);
            SubTaskSpec spec = specs == null || i >= specs.size() ? null : specs.get(i);
            SubTaskType type = spec == null ? SubTaskType.VERIFY : spec.getType();
            Integer maxSteps = spec == null ? null : spec.getMaxSteps();
            SubTask sub = SubTask.builder()
                    .taskId(taskId)
                    .planId(planId)
                    .title(spec == null ? "VERIFY" : spec.getTitle())
                    .description(spec == null ? "Run project verification." : spec.getDescription())
                    .type(type)
                    .status(SubTaskStatus.PENDING)
                    .dependsOn(spec == null ? List.of() : spec.getDependsOn())
                    .createdAt(Instant.now())
                    .attempts(0)
                    .artifacts(new ArrayList<>())
                    .checkpoints(new ArrayList<>())
                    .maxSteps(maxSteps)
                    .build();
            repository.saveSubTask(sub);
            subs.put(taskId, sub);
        }

        this.activePlan = plan;
        this.activeSubtasks = subs;
        publisher.publish(new TaskPlanCreatedEvent(planId, Instant.now(), goal, subs.size()));
        // part5 §8.5: MEMORY.md 增加任务索引条目
        indexSync.notePlan(planId);
        log.info("plan created: {} goal='{}' subtasks={}", planId, goal, subs.size());
        return plan;
    }

    /** 设置外部恢复的 plan（例如 /resume 入口）。 */
    public void adoptPlan(TaskPlan plan, List<SubTask> subs) {
        this.activePlan = plan;
        Map<String, SubTask> map = new LinkedHashMap<>();
        for (SubTask s : subs) map.put(s.getTaskId(), s);
        this.activeSubtasks = map;
    }

    /** 替换单个 SubTask（save_checkpoint 工具刷新 done/currentAction/nextStep 用）。 */
    public void adoptSubTask(SubTask sub) {
        activeSubtasks.put(sub.getTaskId(), sub);
        repository.saveSubTask(sub);
    }

    public Optional<TaskPlan> activePlan() {
        return Optional.ofNullable(activePlan);
    }

    public Optional<SubTask> currentSubTask() {
        if (activePlan == null || activePlan.getCurrentTaskId() == null) return Optional.empty();
        return Optional.ofNullable(activeSubtasks.get(activePlan.getCurrentTaskId()));
    }

    /** 给 context assembler 用的查表入口。 */
    public Optional<SubTask> findSubTaskForRender(String taskId) {
        return Optional.ofNullable(activeSubtasks.get(taskId));
    }

    public Map<String, SubTask> snapshotSubtasks() {
        return new HashMap<>(activeSubtasks);
    }

    public SubTask requireSubTask(String taskId) {
        SubTask sub = activeSubtasks.get(taskId);
        if (sub == null) {
            throw new TaskSchedulerException("subtask not found: " + taskId);
        }
        return sub;
    }

    public TaskPlan requireActivePlan() {
        if (activePlan == null) {
            throw new TaskSchedulerException("no active plan");
        }
        return activePlan;
    }

    // ==================== 状态机推进（工具调用）====================

    public SubTask markStarted(String taskId) {
        TaskPlan plan = requireActivePlan();
        SubTask sub = requireSubTask(taskId);
        if (sub.getStatus() != SubTaskStatus.PENDING && sub.getStatus() != SubTaskStatus.FAILED) {
            throw new TaskSchedulerException("subtask " + taskId + " cannot start (status=" + sub.getStatus() + ")");
        }
        if (!scheduler.dependenciesReady(plan, activeSubtasks, sub)) {
            throw new TaskSchedulerException("subtask " + taskId + " dependencies not ready");
        }
        SubTask started = sub
                .withStatus(SubTaskStatus.IN_PROGRESS)
                .withStartedAt(Instant.now())
                .withAttempts(sub.getAttempts() + 1)
                .withFailureReason(null);
        activeSubtasks.put(taskId, started);
        repository.saveSubTask(started);

        TaskPlan updated = plan.withCurrentTaskId(taskId);
        activePlan = updated;
        repository.savePlan(updated);

        publisher.publish(new SubTaskStartedEvent(
                plan.getPlanId(), taskId, Instant.now(), sub.getDependsOn() == null ? List.of() : sub.getDependsOn(), sub.getTitle()));
        return started;
    }

    public SubTask markComplete(String taskId, List<String> artifacts, String note) {
        SubTask sub = requireSubTask(taskId);
        if (sub.getStatus() != SubTaskStatus.IN_PROGRESS) {
            throw new TaskSchedulerException("subtask " + taskId + " not in progress (status=" + sub.getStatus() + ")");
        }
        List<String> merged = new ArrayList<>(sub.getArtifacts());
        if (artifacts != null) {
            for (String a : artifacts) if (a != null && !a.isBlank() && !merged.contains(a)) merged.add(a);
        }
        SubTask completed = sub
                .withStatus(SubTaskStatus.COMPLETED)
                .withArtifacts(merged)
                .withCompletedAt(Instant.now())
                .withProgress(sub.getDone(), sub.getCurrentAction(), sub.getNextStep())
                .withFailureReason(null);
        if (note != null && !note.isBlank()) {
            completed = completed.withProgress(
                    appendIfPresent(completed.getDone(), "[note] " + note),
                    completed.getCurrentAction(),
                    completed.getNextStep());
        }
        activeSubtasks.put(taskId, completed);
        repository.saveSubTask(completed);

        TaskPlan plan = requireActivePlan();
        publisher.publish(new SubTaskCompletedEvent(
                plan.getPlanId(), taskId, Instant.now(),
                completed.getAttempts(), completed.getDone()));
        return completed;
    }

    public SubTask markFail(String taskId, String reason) {
        SubTask sub = requireSubTask(taskId);
        if (sub.getStatus() != SubTaskStatus.IN_PROGRESS) {
            throw new TaskSchedulerException("subtask " + taskId + " not in progress (status=" + sub.getStatus() + ")");
        }
        SubTask failed = sub
                .withStatus(SubTaskStatus.FAILED)
                .withCompletedAt(Instant.now())
                .withFailureReason(reason == null ? "fail" : reason);
        activeSubtasks.put(taskId, failed);
        repository.saveSubTask(failed);

        TaskPlan plan = requireActivePlan();
        publisher.publish(new SubTaskFailedEvent(
                plan.getPlanId(), taskId, Instant.now(), failed.getFailureReason(), failed.getAttempts()));

        // 链式 SKIP 下游
        cascadeSkip(plan, taskId, "upstream FAILED: " + failed.getFailureReason());
        return failed;
    }

    public SubTask markSkip(String taskId, String reason) {
        SubTask sub = requireSubTask(taskId);
        // part5 §8.7 规则 3: VERIFY 不可被 SKIPPED —— 收尾闭环的强制约束
        if (sub.getType() == SubTaskType.VERIFY) {
            throw new TaskSchedulerException(
                    "VERIFY SubTask cannot be SKIPPED (part5 §8.7)");
        }
        SubTask skipped = sub
                .withStatus(SubTaskStatus.SKIPPED)
                .withCompletedAt(Instant.now())
                .withFailureReason(reason == null ? "skip" : reason);
        activeSubtasks.put(taskId, skipped);
        repository.saveSubTask(skipped);

        TaskPlan plan = requireActivePlan();
        cascadeSkip(plan, taskId, reason);
        return skipped;
    }

    public Checkpoint saveCheckpoint(String taskId, List<String> files, List<String> functions, String note) {
        SubTask sub = requireSubTask(taskId);
        String id = "ck-" + (sub.getCheckpoints().size() + 1);
        Checkpoint ck = Checkpoint.manual(id, files, functions, note);
        SubTask updated = sub.withCheckpoint(ck);
        activeSubtasks.put(taskId, updated);
        repository.saveSubTask(updated);
        return ck;
    }

    // ==================== 推进 plan ====================

    /**
     * 串行驱动当前 plan 直到不能再推进或用户中断（part5 §8.4 / §8.8）。
     *
     * <p>对外暴露的执行入口。串行单线程：一个 SubTask 跑完 → 推进状态机 → 选下一个 → 跑。
     * 当所有 SubTask 都是终态（VERIFIED / FAILED / SKIPPED）或 plan paused 时退出。
     */
    public void runActivePlan() {
        TaskPlan plan = activePlan;
        if (plan == null) {
            log.info("runActivePlan: no active plan");
            return;
        }
        if (plan.getStatus() != TaskPlanStatus.ACTIVE) {
            log.info("runActivePlan: plan {} is not active (status={})", plan.getPlanId(), plan.getStatus());
            return;
        }

        AtomicInteger totalSteps = new AtomicInteger(0);
        int safetyBound = plan.getSubtaskIds().size() * (config.getMaxVerifyFailures() + 3) + 8;
        int iterations = 0;

        while (iterations++ < safetyBound) {
            if (plan.getStatus() != TaskPlanStatus.ACTIVE) break;
            if (plan.isPaused()) {
                log.info("plan {} paused", plan.getPlanId());
                break;
            }

            Optional<SubTask> next = scheduler.selectNext(plan, activeSubtasks);
            if (next.isEmpty()) {
                // 没有可推进的 PENDING —— 要么都跑完了，要么上游失败导致下游 BLOCKED
                if (allTerminal()) {
                    finalize(plan, totalSteps.get());
                }
                break;
            }

            SubTask sub = next.get();
            markStarted(sub.getTaskId());
            TaskLoopObserver observer = new TaskLoopObserver(sub.getTaskId());

            SubTaskOutcome outcome = executor.execute(plan, sub, observer);
            totalSteps.addAndGet(outcome.getStepsTaken());

            // 自动 checkpoint —— SubTask 完成（任意路径）后写一条元信息
            appendAutomaticCheckpoint(sub, observer);

            switch (outcome.getKind()) {
                case COMPLETED_EXPLICIT -> {
                    completeAfterRun(sub, outcome);
                }
                case FAILED_EXPLICIT, BUDGET_EXHAUSTED, ERROR -> {
                    failAfterRun(sub, outcome);
                }
                case SKIPPED_EXPLICIT -> {
                    skipAfterRun(sub, outcome);
                }
            }

            // 重新拉 plan（completeAfterRun/failAfterRun 可能改了 edges 或 currentTaskId）
            plan = activePlan;
        }

        if (iterations >= safetyBound) {
            log.warn("runActivePlan hit safety bound {} for plan {}; abandoning", safetyBound, plan.getPlanId());
            abandonActivePlan("safety bound exceeded");
        }
    }

    public void pauseActivePlan() {
        TaskPlan plan = activePlan;
        if (plan == null) return;
        TaskPlan paused = plan.withPaused(true);
        activePlan = paused;
        repository.savePlan(paused);
    }

    public void resumeActivePlan() {
        TaskPlan plan = activePlan;
        if (plan == null) return;
        TaskPlan resumed = plan.withPaused(false);
        activePlan = resumed;
        repository.savePlan(resumed);
    }

    public void abandonActivePlan(String reason) {
        TaskPlan plan = activePlan;
        if (plan == null) return;
        TaskPlan abandoned = plan
                .withStatus(TaskPlanStatus.ABANDONED)
                .withCurrentTaskId(null);
        activePlan = abandoned;
        repository.savePlan(abandoned);
        // part5 §8.5: 完成/abandoned 后从 MEMORY.md 移除条目
        indexSync.removePlan(plan.getPlanId());
        publisher.publish(new PlanFinishedEvent(plan.getPlanId(), Instant.now(),
                plan.getSubtaskIds().size(), 0,
                "ABANDONED: " + (reason == null ? "unknown" : reason)));
    }

    // ==================== 内部：状态推进 ====================

    private void completeAfterRun(SubTask sub, SubTaskOutcome outcome) {
        SubTask completed = markComplete(sub.getTaskId(), null, outcome.getNote());
        if (completed.getType() == SubTaskType.VERIFY) {
            runVerify(completed);
        } else {
            // 非 VERIFY 子任务没有独立构建校验，完成即视为 VERIFIED（供下游依赖判定与 plan 收尾）。
            SubTask verified = completed.withStatus(SubTaskStatus.VERIFIED);
            activeSubtasks.put(verified.getTaskId(), verified);
            repository.saveSubTask(verified);
        }
        // 清空 currentTaskId 留给下一次 selectNext 选
        TaskPlan plan = requireActivePlan();
        TaskPlan updated = plan.withCurrentTaskId(null);
        activePlan = updated;
        repository.savePlan(updated);
    }

    private void runVerify(SubTask verifySub) {
        TaskPlan plan = requireActivePlan();
        VerifyCommandTemplate template = verifyRunner.detect(config.getProjectRoot());
        Path logPath = repository.verifyLogPath(plan.getPlanId());

        publisher.publish(new VerifyStartedEvent(plan.getPlanId(), verifySub.getTaskId(),
                Instant.now(), template.renderCommand()));

        VerifyResult result = verifyRunner.run(config.getProjectRoot(), template, logPath);

        if (result.passed()) {
            SubTask verified = verifySub.withStatus(SubTaskStatus.VERIFIED)
                    .withCompletedAt(Instant.now());
            activeSubtasks.put(verified.getTaskId(), verified);
            repository.saveSubTask(verified);
            publisher.publish(new VerifyPassedEvent(plan.getPlanId(), verifySub.getTaskId(),
                    Instant.now(), result.getExitCode(), logPath.toString()));
        } else {
            int attempt = plan.getVerifyAttempts() + 1;
            TaskPlan planWithAttempts = plan.withVerifyAttempts(attempt);
            activePlan = planWithAttempts;
            repository.savePlan(planWithAttempts);

            publisher.publish(new VerifyFailedEvent(plan.getPlanId(), verifySub.getTaskId(),
                    Instant.now(), result.getExitCode(), logPath.toString(), attempt));

            // 把 verify 重新标记为 FAILED（因为它没通过）
            SubTask failedVerify = verifySub
                    .withStatus(SubTaskStatus.FAILED)
                    .withCompletedAt(Instant.now())
                    .withFailureReason("verify failed (exit " + result.getExitCode() + ")"
                            + ", tail=" + truncate(result.getLogTail(), 500));
            activeSubtasks.put(verifySub.getTaskId(), failedVerify);
            repository.saveSubTask(failedVerify);

            if (attempt >= config.getMaxVerifyFailures()) {
                TaskPlan abandoned = planWithAttempts.withStatus(TaskPlanStatus.ABANDONED);
                activePlan = abandoned;
                repository.savePlan(abandoned);
                publisher.publish(new PlanFinishedEvent(plan.getPlanId(), Instant.now(),
                        plan.getSubtaskIds().size(), 0,
                        "ABANDONED: verify failed " + attempt + " times"));
                return;
            }
            insertFixSubTask(failedVerify, result);
        }
    }

    private void insertFixSubTask(SubTask failedVerify, VerifyResult result) {
        TaskPlan plan = requireActivePlan();
        // FIX 继承 VERIFY 全部上游依赖
        List<String> parentsOfFix = new ArrayList<>(plan.predecessorsOf(failedVerify.getTaskId()));
        String fixId = "st-" + (plan.getSubtaskIds().size() + 1);
        SubTask fix = SubTask.builder()
                .taskId(fixId)
                .planId(plan.getPlanId())
                .title("FIX — repair build failures from verify")
                .description("Fix the errors surfaced by verify. Verify output tail:\n"
                        + truncate(result.getLogTail(), 1500))
                .type(SubTaskType.FIX)
                .status(SubTaskStatus.PENDING)
                .dependsOn(parentsOfFix)
                .createdAt(Instant.now())
                .attempts(0)
                .artifacts(new ArrayList<>())
                .checkpoints(new ArrayList<>())
                .build();
        activeSubtasks.put(fixId, fix);
        repository.saveSubTask(fix);

        // VERIFY 复位为 PENDING —— FIX 完成后重新验证（part5 §8.7 FIX→重验闭环）。
        SubTask verifyReset = failedVerify
                .withStatus(SubTaskStatus.PENDING)
                .withFailureReason(null);
        activeSubtasks.put(failedVerify.getTaskId(), verifyReset);
        repository.saveSubTask(verifyReset);

        // 局部改图：FIX 插到 VERIFY 之前。
        // - FIX 依赖原 VERIFY 的所有上游（保持 impl → fix）
        // - 追加 FIX → VERIFY，使 VERIFY 需等 FIX VERIFIED 后才重跑
        List<String> newSubtaskIds = new ArrayList<>(plan.getSubtaskIds());
        if (!newSubtaskIds.contains(fixId)) newSubtaskIds.add(fixId);

        TaskPlan updated = plan.withEdgeInserted(
                fixId, parentsOfFix, List.of(failedVerify.getTaskId()), newSubtaskIds);
        activePlan = updated;
        repository.savePlan(updated);
    }

    private void failAfterRun(SubTask sub, SubTaskOutcome outcome) {
        String reason = outcome.getFailureReason();
        if (outcome.getKind() == SubTaskOutcome.Kind.BUDGET_EXHAUSTED) {
            reason = "step budget exhausted";
        }
        markFail(sub.getTaskId(), reason);
        TaskPlan plan = requireActivePlan();
        TaskPlan updated = plan.withCurrentTaskId(null);
        activePlan = updated;
        repository.savePlan(updated);
    }

    private void skipAfterRun(SubTask sub, SubTaskOutcome outcome) {
        markSkip(sub.getTaskId(), outcome.getFailureReason());
        TaskPlan plan = requireActivePlan();
        TaskPlan updated = plan.withCurrentTaskId(null);
        activePlan = updated;
        repository.savePlan(updated);
    }

    private void cascadeSkip(TaskPlan plan, String failedTaskId, String reason) {
        // part5 §8.3 / §8.9: 上游 FAILED/SKIPPED 导致的下游默认直接置 SKIPPED
        // —— 设计语义是"BLOCKED 超时(默认 0 步)" 等价于"立即 SKIPPED",
        // 本实现不引入 BLOCKED 中间态,直接走终态 SKIPPED。
        for (String downstream : plan.successorsOf(failedTaskId)) {
            SubTask sub = activeSubtasks.get(downstream);
            if (sub == null) continue;
            if (sub.getStatus() != SubTaskStatus.PENDING) continue;
            // 任一上游 FAILED/SKIPPED，则该子任务的依赖永远无法满足 → 直接 SKIPPED。
            boolean anyBlocked = plan.predecessorsOf(downstream).stream()
                    .map(activeSubtasks::get)
                    .filter(Objects::nonNull)
                    .anyMatch(s -> s.getStatus() == SubTaskStatus.FAILED
                            || s.getStatus() == SubTaskStatus.SKIPPED);
            if (anyBlocked) {
                SubTask skipped = sub
                        .withStatus(SubTaskStatus.SKIPPED)
                        .withFailureReason(reason);
                activeSubtasks.put(downstream, skipped);
                repository.saveSubTask(skipped);
                cascadeSkip(plan, downstream, reason);
            }
        }
    }

    private void appendAutomaticCheckpoint(SubTask sub, TaskLoopObserver observer) {
        if (sub == null || observer == null) return;
        List<String> files = new ArrayList<>(observer.touchedFiles());
        if (files.isEmpty()) return;
        String id = "ck-auto-" + (sub.getCheckpoints().size() + 1);
        String note = "auto: tools touched " + files.size() + " file(s)";
        Checkpoint ck = Checkpoint.automatic(id, files, note);
        SubTask updated = sub.withCheckpoint(ck);
        activeSubtasks.put(sub.getTaskId(), updated);
        repository.saveSubTask(updated);
    }

    private boolean allTerminal() {
        for (SubTask s : activeSubtasks.values()) {
            if (!s.getStatus().isTerminal()) return false;
        }
        return true;
    }

    private void finalize(TaskPlan plan, int totalSteps) {
        boolean allVerified = activeSubtasks.values().stream()
                .allMatch(s -> s.getStatus() == SubTaskStatus.VERIFIED);
        TaskPlanStatus newStatus = allVerified ? TaskPlanStatus.COMPLETED : TaskPlanStatus.ABANDONED;
        TaskPlan updated = plan
                .withStatus(newStatus)
                .withCurrentTaskId(null);
        activePlan = updated;
        repository.savePlan(updated);
        // part5 §8.5: plan 完成后不再占 MEMORY.md 索引位（中期晋升留给后续）
        indexSync.removePlan(plan.getPlanId());
        publisher.publish(new PlanFinishedEvent(plan.getPlanId(), Instant.now(),
                plan.getSubtaskIds().size(), totalSteps,
                newStatus == TaskPlanStatus.COMPLETED ? "COMPLETED" : "ABANDONED"));
    }

    private static String appendIfPresent(String base, String addition) {
        if (base == null || base.isBlank()) return addition;
        return base + "\n" + addition;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(truncated)";
    }

    // ==================== 查询 ====================

    public String queryPlanAsText(String planId) {
        TaskPlan plan = activePlan;
        if (plan == null || !plan.getPlanId().equals(planId)) return "(no active plan)";
        StringBuilder sb = new StringBuilder();
        sb.append("Plan ").append(plan.getPlanId())
                .append(" · status=").append(plan.getStatus())
                .append(" · paused=").append(plan.isPaused())
                .append(" · current=").append(plan.getCurrentTaskId() == null ? "-" : plan.getCurrentTaskId())
                .append("\nGoal: ").append(plan.getGoal()).append("\n\n");

        int done = 0;
        for (String taskId : plan.getSubtaskIds()) {
            SubTask sub = activeSubtasks.get(taskId);
            if (sub == null) continue;
            String mark = switch (sub.getStatus()) {
                case VERIFIED -> "✓✓";
                case COMPLETED -> "✓ ";
                case IN_PROGRESS -> "⋯";
                case FAILED -> "✗ ";
                case SKIPPED -> "— ";
                case BLOCKED -> "⊘ ";
                case PENDING -> "· ";
            };
            if (sub.getStatus() == SubTaskStatus.VERIFIED || sub.getStatus() == SubTaskStatus.COMPLETED) done++;
            sb.append(String.format("%s [%-11s] %s  %s%n",
                    mark, sub.getStatus(), sub.getTaskId(), sub.getTitle()));
            if (sub.getStatus() == SubTaskStatus.FAILED && sub.getFailureReason() != null) {
                sb.append("    reason: ").append(sub.getFailureReason()).append("\n");
            }
        }
        sb.append("\nprogress: ").append(done).append("/").append(plan.getSubtaskIds().size()).append("\n");
        return sb.toString();
    }
}
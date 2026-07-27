package org.example.agent.core.task.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskSpec;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.orchestrator.TaskLoopObserver;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.core.task.scheduler.TaskSchedulerException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务系统工具集（part5 §8.8）。
 *
 * <p>7 个 @Tool 暴露给 LLM：
 * <ul>
 *   <li>create_plan: 创建 TaskPlan，移交 orchestrator 调度（拆解 Loop 随即退出）</li>
 *   <li>start_subtask: PENDING/FAILED → IN_PROGRESS</li>
 *   <li>complete_subtask: IN_PROGRESS → COMPLETED</li>
 *   <li>fail_subtask: IN_PROGRESS → FAILED</li>
 *   <li>query_plan: 返回当前 plan 全量状态</li>
 *   <li>skip_subtask: 链式跳过下游</li>
 *   <li>save_checkpoint: 关键节点追加 checkpoint</li>
 * </ul>
 *
 * <p>每个工具在生效的同时联动 {@link TaskLoopObserver}，让 orchestrator 在 ReAct 收尾时
 * 能从 observer 读到结果。
 */
@Slf4j
@Component
public class TaskPlanTools {

    private final TaskOrchestrator orchestrator;
    private final ObjectMapper mapper;
    private final TaskLoopObserverHub observerHub;

    @Autowired
    public TaskPlanTools(TaskOrchestrator orchestrator,
                         ObjectMapper mapper,
                         @Lazy TaskLoopObserverHub observerHub) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
        this.observerHub = observerHub;
    }

    @Tool(description = "创建一个 TaskPlan 来拆解复杂任务。"
            + "调用后此 ReAct 循环会退出,由 orchestrator 串行驱动 SubTask。"
            + "subtasks 必须是合法 JSON 数组，每项含 title/description/type/dependsOn/maxSteps(可选)。"
            + "如果 LLM 漏加 VERIFY，orchestrator 会自动追加一个 mvn/gradle/npm 验证子任务。")
    public String createPlan(
            @ToolParam(description = "计划目标，自然语言一句话") String goal,
            @ToolParam(description = "子任务 JSON 数组，例如 [{\"title\":\"...\",\"type\":\"IMPLEMENT\",\"dependsOn\":[]}]")
                    String subtasksJson,
            @ToolParam(description = "可选 sessionId，缺省取当前 active session")
                    String sessionId) {
        List<SubTaskSpec> specs;
        try {
            specs = mapper.readValue(subtasksJson, new TypeReference<List<SubTaskSpec>>() {});
        } catch (Exception ex) {
            return "[error] invalid subtasks JSON: " + ex.getMessage();
        }
        try {
            TaskPlan plan = orchestrator.createPlan(goal, specs, sessionId == null || sessionId.isBlank() ? null : sessionId);
            return "plan created: " + plan.getPlanId() + " with " + plan.getSubtaskIds().size() + " subtasks. "
                    + "The current ReAct loop will exit and orchestrator takes over.";
        } catch (TaskSchedulerException ex) {
            return "[error] " + ex.getMessage();
        }
    }

    @Tool(description = "把指定 SubTask 标记为 IN_PROGRESS（必须 PENDING 或 FAILED 状态，且依赖已 VERIFIED）。")
    public String startSubtask(
            @ToolParam(description = "SubTask id，例如 st-1") String taskId) {
        try {
            SubTask sub = orchestrator.markStarted(taskId);
            return "started " + taskId + " (" + sub.getTitle() + ")";
        } catch (TaskSchedulerException ex) {
            return "[error] " + ex.getMessage();
        }
    }

    @Tool(description = "标记当前 SubTask 完成（IN_PROGRESS → COMPLETED）。"
            + "如果是 VERIFY 类型，orchestrator 会自动跑 mvn/gradle 验证；"
            + "验证通过才进入 VERIFIED；连续 2 次失败则触发 FIX 子任务。")
    public String completeSubtask(
            @ToolParam(description = "SubTask id") String taskId,
            @ToolParam(description = "本次产生的文件路径列表（项目相对路径或绝对路径）", required = false)
                    List<String> artifacts,
            @ToolParam(description = "可选说明", required = false) String note) {
        try {
            SubTask sub = orchestrator.markComplete(taskId, artifacts, note);
            notifyObserver(sub.getTaskId(), o -> o.markCompleteSubtask(note));
            return "completed " + taskId;
        } catch (TaskSchedulerException ex) {
            return "[error] " + ex.getMessage();
        }
    }

    @Tool(description = "显式放弃当前 SubTask（IN_PROGRESS → FAILED）。下游 SubTask 会被链式 SKIPPED。")
    public String failSubtask(
            @ToolParam(description = "SubTask id") String taskId,
            @ToolParam(description = "失败原因") String reason) {
        try {
            SubTask sub = orchestrator.markFail(taskId, reason);
            notifyObserver(sub.getTaskId(), o -> o.markFailSubtask(reason));
            return "failed " + taskId + ": " + sub.getFailureReason();
        } catch (TaskSchedulerException ex) {
            return "[error] " + ex.getMessage();
        }
    }

    @Tool(description = "查询当前 plan 的完整状态（人类可读文本）。")
    public String queryPlan() {
        return orchestrator.activePlan()
                .map(TaskPlan::getPlanId)
                .map(orchestrator::queryPlanAsText)
                .orElse("(no active plan)");
    }

    @Tool(description = "跳过指定 SubTask（不进入 FAILED，仅标记 SKIPPED），并链式 SKIP 其下游。")
    public String skipSubtask(
            @ToolParam(description = "SubTask id") String taskId,
            @ToolParam(description = "跳过原因") String reason) {
        try {
            SubTask sub = orchestrator.markSkip(taskId, reason);
            notifyObserver(sub.getTaskId(), o -> o.markSkipSubtask(reason));
            return "skipped " + taskId;
        } catch (TaskSchedulerException ex) {
            return "[error] " + ex.getMessage();
        }
    }

    @Tool(description = "在当前 SubTask 上打一个 checkpoint（最小元信息：files/functions/note），"
            + "同时把 done/currentAction/nextStep 三个字段一并刷新，供中断恢复时接续。")
    public String saveCheckpoint(
            @ToolParam(description = "SubTask id") String taskId,
            @ToolParam(description = "本次 checkpoint 涉及的文件路径列表") List<String> files,
            @ToolParam(description = "涉及的函数/方法名列表（可选）", required = false) List<String> functions,
            @ToolParam(description = "一句话进度说明") String note,
            @ToolParam(description = "已完成的整体摘要（自然语言一段）", required = false) String done,
            @ToolParam(description = "当前正在进行的动作（实时更新）", required = false) String currentAction,
            @ToolParam(description = "下一步计划", required = false) String nextStep) {
        try {
            Checkpoint ck = orchestrator.saveCheckpoint(taskId, files, functions, note);
            // 顺便更新 done/currentAction/nextStep（如果传了）
            SubTask sub = orchestrator.requireSubTask(taskId);
            if (done != null || currentAction != null || nextStep != null) {
                SubTask refreshed = sub.withProgress(
                        done != null ? done : sub.getDone(),
                        currentAction != null ? currentAction : sub.getCurrentAction(),
                        nextStep != null ? nextStep : sub.getNextStep());
                orchestrator.adoptSubTask(refreshed);
            }
            return "checkpoint saved: " + ck.getCheckpointId();
        } catch (TaskSchedulerException ex) {
            return "[error] " + ex.getMessage();
        }
    }

    private void notifyObserver(String taskId, java.util.function.Consumer<TaskLoopObserver> mutator) {
        TaskLoopObserver obs = observerHub.current();
        if (obs != null && taskId.equals(obs.getTaskId())) {
            mutator.accept(obs);
        }
    }
}
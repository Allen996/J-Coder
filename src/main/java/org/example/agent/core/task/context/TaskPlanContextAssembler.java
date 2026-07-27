package org.example.agent.core.task.context;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.layer.DynamicLayer;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 动态层 {@code task_plan} key 的装配器（part5 §8.8）。
 *
 * <p>装配规则：
 * <ul>
 *   <li>有 active plan → 注入当前 plan 摘要 + 当前 SubTask 标题</li>
 *   <li>无 active plan → 不注入（保持 empty entry）</li>
 *   <li>token 配额 1K（{@link ContextBudgetPolicy#DEFAULT_MID_TERM_QUOTA} 同一档）</li>
 * </ul>
 *
 * <p>由 {@link org.example.agent.context.builder.ContextBuilder#loadDynamicLayer} 调用。
 */
@Slf4j
@Component
public class TaskPlanContextAssembler {

    /** task_plan key 的 token 配额：1K（与 part5 §8.8 一致）。 */
    public static final long TASK_PLAN_QUOTA = 1_024L;

    private final TaskOrchestrator orchestrator;

    public TaskPlanContextAssembler(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 装配 task_plan key 并写入 dynamicLayer。
     */
    public void assemble(DynamicLayer dynamicLayer) {
        if (dynamicLayer == null) return;
        var planOpt = orchestrator.activePlan();
        if (planOpt.isEmpty()) {
            dynamicLayer.put(ContextKey.TASK_PLAN, ContextEntry.empty(ContextKey.TASK_PLAN));
            return;
        }
        TaskPlan plan = planOpt.get();
        String text = render(plan);
        String clipped = clip(text, TASK_PLAN_QUOTA);
        ContextEntry entry = ContextEntry.builder()
                .key(ContextKey.TASK_PLAN)
                .text(clipped.isEmpty() ? null : clipped)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(clipped))
                .lastRefreshedAt(Instant.now())
                .sourceRef("task://" + plan.getPlanId())
                .build();
        dynamicLayer.put(ContextKey.TASK_PLAN, entry);
    }

    private String render(TaskPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Active Plan ").append(plan.getPlanId())
                .append(" · status=").append(plan.getStatus())
                .append(" · paused=").append(plan.isPaused()).append('\n');
        sb.append("Goal: ").append(plan.getGoal()).append('\n');
        sb.append('\n');

        var currentOpt = orchestrator.currentSubTask();
        if (currentOpt.isPresent()) {
            SubTask cur = currentOpt.get();
            sb.append("▶ current SubTask ").append(cur.getTaskId())
                    .append(" (").append(cur.getStatus()).append(") ")
                    .append(cur.getTitle()).append('\n');
            if (cur.getDescription() != null && !cur.getDescription().isBlank()) {
                sb.append("  ").append(cur.getDescription()).append('\n');
            }
            if (cur.getNextStep() != null && !cur.getNextStep().isBlank()) {
                sb.append("  next: ").append(cur.getNextStep()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("SubTasks:\n");
        int done = 0;
        for (String taskId : plan.getSubtaskIds()) {
            var subOpt = findSubTask(plan, taskId);
            if (subOpt.isEmpty()) continue;
            SubTask sub = subOpt.get();
            String mark = switch (sub.getStatus()) {
                case VERIFIED -> "✓✓";
                case COMPLETED -> "✓ ";
                case IN_PROGRESS -> "⋯";
                case FAILED -> "✗ ";
                case SKIPPED -> "— ";
                case BLOCKED -> "⊘ ";
                case PENDING -> "· ";
            };
            sb.append("  ").append(mark).append(' ').append(taskId).append(" · ")
                    .append(sub.getStatus()).append(" · ").append(sub.getTitle()).append('\n');
            if (sub.getStatus() == org.example.agent.core.task.SubTaskStatus.VERIFIED
                    || sub.getStatus() == org.example.agent.core.task.SubTaskStatus.COMPLETED) {
                done++;
            }
        }
        sb.append('\n').append("progress: ").append(done).append("/").append(plan.getSubtaskIds().size());
        return sb.toString();
    }

    private java.util.Optional<SubTask> findSubTask(TaskPlan plan, String taskId) {
        // activeSubtasks 是 orchestrator 私有 —— 通过 query 反查
        return orchestrator.findSubTaskForRender(taskId);
    }

    private static String clip(String text, long maxTokens) {
        if (text == null || text.isEmpty()) return "";
        long maxChars = Math.max(0L, maxTokens) * 4L;
        if (text.length() <= maxChars) return text;
        return text.substring(0, (int) maxChars) + "\n... (truncated)";
    }
}
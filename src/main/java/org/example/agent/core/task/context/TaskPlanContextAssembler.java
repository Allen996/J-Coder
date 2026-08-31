package org.example.agent.core.task.context;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.layer.DynamicLayer;
import org.example.agent.core.task.dag.DagGraph;
import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.dag.DagNodeState;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 动态层 {@code task_plan} key 的装配器（阶段 2 重写）。
 *
 * <p>装配规则：
 * <ul>
 *   <li>有 active plan → 注入当前 DAG 节点列表 + 状态</li>
 *   <li>无 active plan → 不注入（保持 empty entry）</li>
 *   <li>token 配额 1K（与 part5 §8.8 一致）</li>
 * </ul>
 */
@Slf4j
@Component
public class TaskPlanContextAssembler {

    /** task_plan key 的 token 配额：1K。 */
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
        var graphOpt = orchestrator.activeGraph();
        if (graphOpt.isEmpty()) {
            dynamicLayer.put(ContextKey.TASK_PLAN, ContextEntry.empty(ContextKey.TASK_PLAN));
            return;
        }
        DagGraph graph = graphOpt.get();
        String text = render(graph);
        String clipped = clip(text, TASK_PLAN_QUOTA);
        ContextEntry entry = ContextEntry.builder()
                .key(ContextKey.TASK_PLAN)
                .text(clipped.isEmpty() ? null : clipped)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(clipped))
                .lastRefreshedAt(Instant.now())
                .sourceRef("task://" + graph.getPlanId())
                .build();
        dynamicLayer.put(ContextKey.TASK_PLAN, entry);
    }

    private String render(DagGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("Active Plan ").append(graph.getPlanId())
                .append(" · session=").append(graph.getSessionId()).append('\n');
        sb.append("Goal: ").append(graph.getGoal()).append('\n');
        sb.append('\n');

        sb.append("DAG Nodes:\n");
        int done = 0;
        for (DagNode node : graph.getNodes().values()) {
            String mark = switch (node.getState()) {
                case COMPLETED -> "✓ ";
                case IN_PROGRESS -> "⋯";
                case FAILED -> "✗ ";
                case TIMEOUT -> "⏱ ";
                case PENDING -> "· ";
            };
            sb.append("  ").append(mark).append(' ').append(node.getTaskId()).append(" · ")
                    .append(node.getState()).append(" · ").append(node.getTitle()).append('\n');
            if (node.isSuccess()) done++;
        }
        sb.append('\n').append("progress: ").append(done).append("/").append(graph.size());
        return sb.toString();
    }

    private static String clip(String text, long maxTokens) {
        if (text == null || text.isEmpty()) return "";
        long maxChars = Math.max(0L, maxTokens) * 4L;
        if (text.length() <= maxChars) return text;
        return text.substring(0, (int) maxChars) + "\n... (truncated)";
    }
}
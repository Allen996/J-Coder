package org.example.agent.core.task;

/**
 * DAG 边（part5 §8.5 plan.json edges 字段）。
 *
 * <p>与各 SubTask 的 {@code dependsOn} 冗余存一份，便于整体校验无环。
 */
public record PlanEdge(String from, String to) {
    public PlanEdge {
        if (from == null || to == null) {
            throw new IllegalArgumentException("PlanEdge.from/to must be non-null");
        }
    }
}

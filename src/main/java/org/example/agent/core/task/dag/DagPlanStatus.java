package org.example.agent.core.task.dag;

/**
 * Plan 整体状态（阶段 2 引入，替代旧 TaskPlanStatus）。
 *
 * <ul>
 *   <li>RUNNING —— 有节点未到终态</li>
 *   <li>COMPLETED —— 所有节点 COMPLETED</li>
 *   <li>FAILED —— 有节点 FAILED 且主 Agent 决定不再重试（或用户放弃）</li>
 *   <li>ABANDONED —— 用户主动放弃（/exit / /abandon）</li>
 * </ul>
 */
public enum DagPlanStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    ABANDONED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ABANDONED;
    }
}
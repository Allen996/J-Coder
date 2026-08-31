package org.example.agent.core.task.dag;

/**
 * DAG 节点状态机（阶段 2 引入，简化版）。
 *
 * <ul>
 *   <li>PENDING —— 待执行</li>
 *   <li>IN_PROGRESS —— 已派发 SubAgent,正在跑</li>
 *   <li>COMPLETED —— SubAgent 返回 COMPLETED</li>
 *   <li>FAILED —— SubAgent 返回 FAILED（主 Agent 可决定重试 → IN_PROGRESS）</li>
 *   <li>TIMEOUT —— SubAgent 返回 TIMEOUT（主 Agent 可决定重试或换策略）</li>
 * </ul>
 *
 * <p>阶段 2 不再有 VERIFY / SKIPPED / BLOCKED：
 * <ul>
 *   <li>VERIFY 取消 —— SubAgent 自己带报告,不需要独立验证节点</li>
 *   <li>SKIPPED 取消 —— 上游 FAILED 不再级联 SKIP 下游,改由主 Agent 决定是否派下游</li>
 *   <li>BLOCKED 不需要 —— 没有上游失败级联跳过的设计</li>
 * </ul>
 */
public enum DagNodeState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMEOUT;
    }

    public boolean isSuccess() {
        return this == COMPLETED;
    }
}
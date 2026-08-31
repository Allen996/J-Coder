package org.example.agent.core.task.subagent;

/**
 * SubAgent 单次执行的终态（阶段 1 引入）。
 *
 * <p>注意：与 {@code SubTaskStatus} 不同 —— SubAgent 是一次"派发→跑完"的运行实例的状态，
 * 而 SubTask 是 DAG 中节点的持久化状态。
 *
 * <ul>
 *   <li>{@link #COMPLETED} —— SubAgent 正常完成（达到 final report 或 budget 用完但无异常）</li>
 *   <li>{@link #FAILED} —— SubAgent 报告执行失败（含 LLM 显式 fail / 工具反复错误）</li>
 *   <li>{@link #TIMEOUT} —— 超过配置的最大运行时长，被强制中断</li>
 * </ul>
 */
public enum SubAgentStatus {
    COMPLETED,
    FAILED,
    TIMEOUT
}
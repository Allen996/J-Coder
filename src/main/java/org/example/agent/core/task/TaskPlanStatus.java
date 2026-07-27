package org.example.agent.core.task;

/**
 * TaskPlan 总体状态（part5 §8.5 plan.json 字段）。
 *
 * <ul>
 *   <li>ACTIVE: 计划进行中</li>
 *   <li>COMPLETED: 所有子任务 VERIFIED（包括最终 VERIFY 通过）</li>
 *   <li>ABANDONED: VERIFY 连续失败 / 用户主动放弃 / 整个 DAG 失败</li>
 *   <li>PAUSED: 用户中途插入时临时挂起（仍然属于 ACTIVE 范畴，仅 paused 字段为 true）</li>
 * </ul>
 */
public enum TaskPlanStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED
}

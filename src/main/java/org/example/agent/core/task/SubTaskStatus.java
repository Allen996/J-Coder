package org.example.agent.core.task;

/**
 * SubTask 状态机（part5 §8.3）。
 *
 * <p>转换约束：
 * <ul>
 *   <li>PENDING → IN_PROGRESS：所有依赖 VERIFIED</li>
 *   <li>IN_PROGRESS → COMPLETED：LLM 显式调用 complete_subtask</li>
 *   <li>IN_PROGRESS → FAILED：预算耗尽 / 工具反复失败 / LLM 显式放弃</li>
 *   <li>COMPLETED → VERIFIED：仅 VERIFY 子任务可触发</li>
 *   <li>FAILED → PENDING / SKIPPED：LLM 决策</li>
 *   <li>任何 → SKIPPED：上游 FAILED 导致链式跳过</li>
 * </ul>
 *
 * <p>BLOCKED 语义说明（part5 §8.9 "上游失败导致下游 BLOCKED 超时(默认 0 步,直接 SKIPPED)"）：
 * 本实现保留 BLOCKED 枚举值但不在状态机中实际产生该状态 —— 失败上游的下游默认
 * 在 cascadeSkip 中直接置 SKIPPED。等价于 BLOCKED 状态以 0 步超时立即跳过。
 * 后续若需要可观察的 BLOCKED 过渡态，再单独打开。
 */
public enum SubTaskStatus {
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED,
    FAILED,
    SKIPPED,
    VERIFIED;

    public boolean isTerminal() {
        return this == VERIFIED || this == FAILED || this == SKIPPED;
    }

    public boolean isSuccess() {
        return this == VERIFIED || this == COMPLETED;
    }
}

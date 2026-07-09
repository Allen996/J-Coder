package org.example.agent.core.reason;

/**
 * 终止原因枚举。AgentExecutionResult.reason 与 FinishEvent.reason 共用。
 *
 * <pre>
 *  FINISH            - 模型自然收敛（Final Answer）
 *  TOKEN_LIMIT       - 累计 token 超 AgentBudget.maxTotalTokens
 *  LOOP_LIMIT        - 步数超 AgentBudget.maxSteps
 *  WALLCLOCK_LIMIT   - wallclock 超 AgentBudget.maxWallClock
 *  CONTEXT_OVERFLOW  - 单次 LLM 调用的 prompt 超出「窗口 - 记忆 - 单轮阈值」，
 *                      压缩钩子未接或压缩后仍超；详见 TokenBudgetObserver
 *  CANCELLED         - 调用方主动 cancel(executionId)
 *  ERROR             - 内部异常，详见 LoopErrorEvent
 * </pre>
 */
public enum FinishReason {
    FINISH,
    TOKEN_LIMIT,
    LOOP_LIMIT,
    WALLCLOCK_LIMIT,
    CONTEXT_OVERFLOW,
    CANCELLED,
    ERROR
}

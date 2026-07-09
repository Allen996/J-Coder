package org.example.agent.core.observer;

/**
 * TokenBudgetObserver 在检测到 per-call prompt 超出有效上下文空间时触发的压缩钩子。
 *
 * <p>设计意图：4.1 阶段压缩模块尚未落地，先把「先压缩、后终止」的契约固化下来——
 * observer 拿到溢出信号时优先回调本接口，调用方负责压缩消息历史；如果压缩后下一次
 * LLM 调用仍然超界，observer 才会用 {@code CONTEXT_OVERFLOW} 终止。
 *
 * <p>4.1 阶段默认实现是空操作（hook 未注入时降级为「首次溢出即终止」）。
 * 4.x 阶段把压缩模块挂上后，本接口会接 agent-context 的 ContextCompressor。
 */
@FunctionalInterface
public interface ContextCompressionHook {

    /**
     * 尝试压缩当前会话的消息历史以容纳下一次 LLM 调用。
     *
     * @param executionId       当前执行 id
     * @param promptTokens      本次溢出时观察到的 prompt token 数
     * @param effectiveBudget   上下文窗口的 per-call 有效 prompt 上限（contextWindow - memory - completion）
     * @return true  表示压缩成功，下一次调用预计能装下；observer 继续放行
     *         false 表示压缩失败或无可压缩内容，observer 应当终止执行
     */
    boolean tryCompress(String executionId, long promptTokens, long effectiveBudget);
}
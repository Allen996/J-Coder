package org.example.agent.core.observer;

import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.signal.ReActLoopSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 预算观察者，覆盖两类检查：
 * <ol>
 *   <li><b>per-call 上下文有效空间检查</b>：每次 ThoughtEvent 的 prompt tokens 必须落在
 *       {@code AgentBudget.getEffectivePerCallPromptBudget()}（= contextWindowMax -
 *       memoryTokenReservation - maxSingleCallCompletion）以内。首次越界先回调
 *       {@link ContextCompressionHook}（4.1 阶段未注入时降级为直接终止），压缩失败 / 不存在
 *       钩子 / 压缩后仍超 → 以 {@code CONTEXT_OVERFLOW} 终止。</li>
 *   <li><b>累计 prompt+completion 检查</b>：累加到 {@code AgentBudget.maxTotalTokens}
 *       触发 {@code TOKEN_LIMIT} 终止。</li>
 * </ol>
 *
 * <p>每个 execution 持有一份实例；AgentRuntimeImpl 在 subscribe 之初创建并只在这条流上传播。
 * 线程安全由 AtomicLong + signal 的 atomic 语义保证。
 */
public class TokenBudgetObserver implements ReActLoopObserver {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetObserver.class);

    private final AgentBudget budget;
    private final ContextCompressionHook compressionHook;
    private final long maxTokens;
    private final long effectivePerCallPromptBudget;

    private final AtomicLong used = new AtomicLong(0);
    private volatile long tokensWhenBudgetHit;
    private volatile long lastPerCallPrompt;
    private volatile int perCallOverflowCount;
    private volatile boolean contextOverflowTerminated;

    public TokenBudgetObserver(AgentBudget budget) {
        this(budget, null);
    }

    public TokenBudgetObserver(AgentBudget budget, ContextCompressionHook compressionHook) {
        this.budget = budget;
        this.compressionHook = compressionHook;
        this.maxTokens = budget.getMaxTotalTokens();
        this.effectivePerCallPromptBudget = budget.getEffectivePerCallPromptBudget();
    }

    public long getMaxTokens() { return maxTokens; }
    public long getUsed() { return used.get(); }
    public long getTokensWhenBudgetHit() { return tokensWhenBudgetHit; }

    public long getEffectivePerCallPromptBudget() { return effectivePerCallPromptBudget; }
    public long getLastPerCallPrompt() { return lastPerCallPrompt; }
    public int getPerCallOverflowCount() { return perCallOverflowCount; }
    public boolean isContextOverflowTerminated() { return contextOverflowTerminated; }

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        long prompt = event.getPromptTokens();
        long completion = event.getCompletionTokens();

        // 1) per-call 上下文有效空间检查（先于累计，避免污染 used）
        if (effectivePerCallPromptBudget != Long.MAX_VALUE
                && prompt > effectivePerCallPromptBudget
                && !signal.isTerminateRequested()) {
            lastPerCallPrompt = prompt;
            perCallOverflowCount++;
            log.warn("executionId={} per-call prompt exceeds effective context space: prompt={}, effective={} "
                            + "(window={}, memory={}, completion_threshold={})",
                    event.getExecutionId(), prompt, effectivePerCallPromptBudget,
                    budget.getContextWindowMax(), budget.getMemoryTokenReservation(),
                    budget.getMaxSingleCallCompletion());

            boolean compressed = invokeCompressionHook(event.getExecutionId(), prompt);
            if (!compressed) {
                contextOverflowTerminated = true;
                log.warn("executionId={} context compression unavailable/failed, terminating with CONTEXT_OVERFLOW",
                        event.getExecutionId());
                signal.requestTerminate(FinishReason.CONTEXT_OVERFLOW);
            }
            // 压缩成功时不污染 used；让下一次 thought 自然落到累计检查
            return;
        }

        // 2) 累计 prompt + completion 检查（原有行为）
        long total = used.addAndGet(prompt + completion);
        if (maxTokens > 0 && total > maxTokens && !signal.isTerminateRequested()) {
            tokensWhenBudgetHit = total;
            log.info("executionId={} token budget exceeded: used={}, max={}",
                    event.getExecutionId(), total, maxTokens);
            signal.requestTerminate(FinishReason.TOKEN_LIMIT);
        }
    }

    private boolean invokeCompressionHook(String executionId, long promptTokens) {
        if (compressionHook == null) {
            return false;
        }
        try {
            return compressionHook.tryCompress(executionId, promptTokens, effectivePerCallPromptBudget);
        } catch (Exception ex) {
            log.warn("compression hook threw exception, treating as failure", ex);
            return false;
        }
    }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) {
        log.warn("executionId={} token budget snapshot: used={}, max={}",
                event.getExecutionId(), event.getTokensUsed(), event.getMaxTokens());
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onActionPreCheck(org.example.agent.core.event.ActionPreCheckEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onActionInvoked(org.example.agent.core.event.ActionInvokedEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onObservation(org.example.agent.core.event.ObservationEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) { /* noop */ }
}
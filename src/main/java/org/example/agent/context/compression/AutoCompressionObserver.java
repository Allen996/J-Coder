package org.example.agent.context.compression;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.signal.ReActLoopSignal;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * step 间隙的自动压缩观测器（part3.md §6.6）。
 *
 * <p><b>状态变更</b>：自 2026-08 起，主要的同步自动压缩触发已内迁到
 * {@link org.example.agent.context.builder.ContextBuilder#build} —— 阈值是
 * {@code (static + dynamic + input) > 0.8 * contextWindowMax}，与主循环同步阻塞，
 * 详见 {@code ContextBuilder.syncCompressMessages}。本 observer 仍挂着，
 * 作为观察点保留；若 ContextBuilder 因 sessionStore==null 等边界条件跳过压缩，
 * 这里仍能兜住（需补全 executionId→sessionId 映射）。
 *
 * <p>历史触发条件 {@code sessionUsed / sessionReserved &gt; 0.8}；触发时机
 * {@code onObservation}。{@link #resolveSessionId(String)} 当前仍为 stub。
 */
@Slf4j
@Component
public class AutoCompressionObserver implements ReActLoopObserver {

    private final ContextBudgetPolicy policy;
    private final SessionMessageStore sessionStore;
    private final ConversationCompressor compressor;

    public AutoCompressionObserver(ContextBudgetPolicy policy,
                                   SessionMessageStore sessionStore,
                                   ConversationCompressor compressor) {
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
        this.sessionStore = sessionStore;
        this.compressor = compressor;
    }

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        // noop —— 选择 observation 后触发
    }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        if (signal.isTerminateRequested()) return;
        String sessionId = resolveSessionId(event.getExecutionId());
        if (sessionId == null) return;
        SessionMessageStore.Session session = sessionStore.get(sessionId);
        if (session == null) return;

        long used = sessionStore.estimateUsedTokens(sessionId);
        if (policy.shouldTriggerCompression(used)) {
            log.info("AutoCompressionObserver: session {} used {} > 80% of dynamic reserved {}, compressing",
                    sessionId, used, policy.dynamicReserved());
            List<Message> history = session.snapshot();
            try {
                org.example.agent.core.task.AgentTask stub = org.example.agent.core.task.AgentTask.builder()
                        .sessionId(sessionId)
                        .input("")
                        .role("chat")
                        .build();
                List<Message> compressed = compressor.loadMessages(history, policy, stub);
                session.replaceAll(compressed);
                session.markCompressed(extractSummaryText(compressed));
            } catch (Exception ex) {
                log.warn("AutoCompressionObserver: compression failed, leaving history unchanged: {}",
                        ex.getMessage(), ex);
            }
        }
    }

    private static String extractSummaryText(List<Message> compressed) {
        if (compressed == null || compressed.isEmpty()) return "";
        // 第一条通常是 system 摘要
        Message first = compressed.get(0);
        return SessionMessageStore.extractText(first);
    }

    /**
     * 把 executionId 推回到 sessionId。当前实现保守为 null（无法从 executionId 反推 sessionId），
     * 留待 part4 持久化 + 任务元数据补全后增强。
     * <p>v1 简化：依赖 {@link ConversationCompressor} 在 {@link org.example.agent.context.builder.ContextBuilder}
     * 装配时调用，主动压缩在 step 间隙触发，自动压缩放在 observer 里属于增强路径。
     */
    private static String resolveSessionId(String executionId) {
        return null;
    }

    // ============ 未使用接口占位 ============

    @Override
    public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) { }

    @Override
    public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) { }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) { }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) { }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) { }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) { }
}
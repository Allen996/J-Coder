package org.example.agent.core.observer;

import lombok.Getter;
import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.signal.ReActLoopSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * 整次执行 + 单步工具调用统一超时闸。
 *
 * 与 Spring AI Alibaba 内部 OkHttp 超时（modelCallTimeout）的关系：本 observer
 * 对应 agent-platform 业务语义上的 wallclock 超时，是兜底；RestClient 级的
 * 单次 HTTP 超时仍由 DashScopeConfig 控制。
 *
 * 起点 baseAt 由 AgentRuntimeImpl 在 subscribe 时告知（通常是 execute 调用时间）。
 */
@Getter
public class TimeoutObserver implements ReActLoopObserver {

    private static final Logger log = LoggerFactory.getLogger(TimeoutObserver.class);

    private final AgentBudget budget;
    private final Instant baseAt;
    private volatile Instant toolInvocationAt;

    public TimeoutObserver(AgentBudget budget, Instant baseAt) {
        this.budget = budget;
        this.baseAt = baseAt;
    }

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        if (budget.getMaxWallClock() == null
                || budget.getMaxWallClock().isZero()
                || budget.getMaxWallClock().isNegative()) {
            return;
        }
        if (Instant.now().isAfter(baseAt.plus(budget.getMaxWallClock()))
                && !signal.isTerminateRequested()) {
            log.warn("executionId={} wallclock budget exceeded at step {}",
                    event.getExecutionId(), event.getStepIndex());
            signal.requestTerminate(FinishReason.WALLCLOCK_LIMIT);
        }
    }

    @Override
    public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) {
        this.toolInvocationAt = Instant.now();
    }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        if (toolInvocationAt == null) {
            return;
        }
        Duration toolBudget = budget.getToolCallTimeout();
        if (toolBudget == null || toolBudget.isZero() || toolBudget.isNegative()) {
            toolInvocationAt = null;
            return;
        }
        Duration elapsed = Duration.between(toolInvocationAt, event.getAt());
        if (elapsed.compareTo(toolBudget) > 0) {
            log.warn("executionId={} tool call budget exceeded: latency={}, budget={}",
                    event.getExecutionId(), elapsed, toolBudget);
        }
        toolInvocationAt = null;
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) { /* noop */ }
}

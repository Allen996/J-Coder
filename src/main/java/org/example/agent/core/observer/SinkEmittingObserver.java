package org.example.agent.core.observer;

import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.AgentEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.signal.ReActLoopSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;

/**
 * 把 observer 链上的事件桥接到 {@link FluxSink}，实现真正的增量发射。
 *
 * 替换 {@code AgentRuntimeImpl#stream()} 里原本的 {@code pushEvents(recorder.getEvents(), sink)}
 * 全量回放模式：事件在 {@code ReActLoop#invokeInternal()} 的 doOnNext 中触发 observer，
 * 本类 onXxx 同步调用 sink.next，下游 Flux 订阅方即可逐条收到。
 *
 * 线程模型：observer 由 ReActLoop 在订阅线程同步触发，FluxSink 默认在同线程推数据，
 * 适合 Reactor 的同一线程消费。如果下游切换线程，Flux 的 buffer/backpressure 会吸收差异。
 *
 * 取消语义：当 sink 被订阅方 cancel，本类继续接收事件但 sink.next 会因
 * {@code FluxSink#isCancelled()} 短路，避免 IllegalStateException。
 */
public class SinkEmittingObserver implements ReActLoopObserver {

    private static final Logger log = LoggerFactory.getLogger(SinkEmittingObserver.class);

    private final FluxSink<AgentEvent> sink;

    public SinkEmittingObserver(FluxSink<AgentEvent> sink) {
        this.sink = sink;
    }

    private void emit(AgentEvent event) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            sink.next(event);
        } catch (Exception ex) {
            log.warn("sink.next failed for {}: {}", event.getType(), ex.getMessage());
        }
    }

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) {
        emit(event);
    }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) {
        emit(event);
    }
}
package org.example.agent.core.observer;

import lombok.Getter;
import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.RollbackEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.signal.ReActLoopSignal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置的事件记录器。是 4.1 阶段把 AgentEvent 流落内存的最简实现。
 *
 * 4.6 阶段（agent-observability）会替换为接 OTel / DecisionLogger 的实现，
 * 本类保留作为「最近事件流」的兜底。
 */
@Getter
public class EventRecordingObserver implements ReActLoopObserver {

    private final List<org.example.agent.core.event.AgentEvent> events =
            Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onRollback(RollbackEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) {
        events.add(event);
    }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) {
        events.add(event);
    }
}

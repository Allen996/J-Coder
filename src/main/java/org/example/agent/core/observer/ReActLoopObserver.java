package org.example.agent.core.observer;

import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.signal.ReActLoopSignal;

/**
 * ReAct 循环的事件订阅接口。
 *
 * AgentRuntime 在内部维护 List<ReActLoopObserver> 组成观察者链，按订阅顺序串行触发。
 * 实现类应该：
 *  - 保持 idempotent（同一事件可能因重放而多次到达）
 *  - 单 listener 异常不能阻断其它 listener（AgentRuntimeImpl 内部 catch）
 *  - 任何想强制中断的 observer 通过入参 ReActLoopSignal.requestTerminate(...)
 *
 * 该接口刻意不继承任何 reactive 接口。AgentRuntimeImpl 在 Flux 订阅链里做
 * 同步分发，下游业务（observability / sandbox / audit）自己再选背压方式。
 */
public interface ReActLoopObserver {

    void onThought(ThoughtEvent event, ReActLoopSignal signal);

    void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal);

    void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal);

    void onObservation(ObservationEvent event, ReActLoopSignal signal);

    void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal);

    void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal);

    void onFinish(FinishEvent event, ReActLoopSignal signal);

    void onError(LoopErrorEvent event, ReActLoopSignal signal);
}

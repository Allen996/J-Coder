package org.example.agent.core.observer;

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
import org.springframework.ai.chat.messages.Message;

import java.util.List;

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

    /**
     * 副作用回滚事件。v1 默认 no-op,审计 / 可观测实现可选择性 override。
     */
    default void onRollback(RollbackEvent event, ReActLoopSignal signal) {
    }

    void onFinish(FinishEvent event, ReActLoopSignal signal);

    void onError(LoopErrorEvent event, ReActLoopSignal signal);

    /**
     * 每次 chatModel.call() 前触发（part3.md 可观测增强）。
     *
     * <p>ReActLoop 在组装完消息列表、调用 LLM 之前同步分发本事件；实现者拿到的是
     * 当前 step 即将发出的完整 prompt（含 system + project + session + 本 step 的
     * assistant/tool 消息）。
     *
     * <p>默认 no-op —— 不感兴趣的 observer 不需要实现。
     */
    default void onPromptBuilt(List<Message> messages, int stepIndex) {
    }
}

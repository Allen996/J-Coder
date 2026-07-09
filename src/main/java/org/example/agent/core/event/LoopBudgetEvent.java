package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * LoopStepObserver 在累计步数达到 AgentBudget.maxSteps 时触发，
 * 紧跟 FinishEvent(reason=LOOP_LIMIT) 一同发出。
 *
 * 同时也会作为信号让 SandboxObserver 等其它观察者停止消费后续事件。
 */
@Getter
@Builder
@ToString
public final class LoopBudgetEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final int maxSteps;
    private final int stepsTaken;

    @Override
    public String getType() {
        return "budget.loop";
    }
}

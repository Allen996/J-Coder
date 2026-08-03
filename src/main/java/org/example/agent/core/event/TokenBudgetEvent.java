package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * TokenBudgetObserver 在累计 tokens 超过 AgentBudget.maxTotalTokens 时触发。
 * 紧跟 FinishEvent(reason=TOKEN_LIMIT)。
 */
@Getter
@Builder
@ToString
public final class TokenBudgetEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final long maxTokens;
    private final long tokensUsed;

    @Override
    public String getType() {
        return "budget.token";
    }
}

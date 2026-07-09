package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * ReAct 循环任意位置抛出未捕获异常时发出。
 *
 * 紧跟一条 reason=ERROR 的 FinishEvent，调用方根据本事件的 message / throwable
 * 决定是否触发用户级 fallback 文案。
 */
@Getter
@Builder
@ToString
public final class LoopErrorEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final String agentName;
    private final String message;
    private final Throwable error;

    @Override
    public String getType() {
        return "error";
    }
}

package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 工具调用已发出（policy 校验通过 / 实际进入 toolGateway.invoke）后触发。
 *
 * AgentRuntimeImpl 在 Spring AI Alibaba 的 AgentToolNode 实际派发前 emit。
 * ActionInvokedEvent 之后一定会跟一条 ObservationEvent（成功 / 失败 / 超时）。
 */
@Getter
@Builder
@ToString
public final class ActionInvokedEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final String agentName;
    private final String toolName;

    @Override
    public String getType() {
        return "action.invoked";
    }
}

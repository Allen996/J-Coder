package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 工具调用结果回写时触发。本事件由 Spring AI Alibaba 的 ToolResponseMessage 产出，
 * AgentRuntimeImpl 在 Flux<NodeOutput> 看到 AGENT_TOOL_FINISHED 时把它转成本结构。
 *
 * observationText 是脱敏 / 截断后的明文；完整 result 由 AuditLogger 在 AuditLogger 落库。
 */
@Getter
@Builder
@ToString
public final class ObservationEvent implements AgentEvent {

    public enum Status { OK, ERROR, TIMEOUT, DENIED }

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final String agentName;
    private final String toolName;
    private final Status status;
    private final String observationText;
    private final long latencyMs;

    @Override
    public String getType() {
        return "observation";
    }
}

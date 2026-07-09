package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 模型思考步骤完成时触发。
 *
 * 一条 ThoughtEvent 通常对应一个 AssistantMessage（含文本与可能的 tool_calls）。
 * 后续 ActionPreCheckEvent / ActionInvokedEvent 会以它为锚点。
 */
@Getter
@Builder
@ToString
public final class ThoughtEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final String agentName;

    /** 模型输出的自然语言内容（不含 tool_calls 标记的原文）。 */
    private final String thoughtText;

    /** 本步模型推理的 prompt tokens。 */
    private final long promptTokens;

    /** 本步模型推理的 completion tokens。 */
    private final long completionTokens;

    @Override
    public String getType() {
        return "thought";
    }
}

package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.reason.FinishReason;

import java.time.Instant;

/**
 * 执行结束的最后一帧事件。每一次 execute / stream 都一定以 FINISH 或
 * LOOP_ERROR 收尾（normal flow + 异常路径都覆盖）。
 *
 * finalAnswer 是 AgentRuntimeImpl 累积的 AssistantMessage 文本，给到调用方即可渲染。
 */
@Getter
@Builder
@ToString
public final class FinishEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final FinishReason reason;
    private final String finalAnswer;
    private final Long totalTokensUsed;

    @Override
    public String getType() {
        return "finish";
    }
}

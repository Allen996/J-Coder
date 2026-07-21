package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * 工具副作用被回滚时发出(LOGIC 失败且目标工具可逆)。
 *
 * <p>{@code rolledTargets} 来自 {@code SideEffectTracker.rollbackAll()} 的逐条描述,
 * 便于 audit / 可观测性把"已经做了哪些撤销"沉淀下来。LLM 收到的 brief 不走本事件,
 * 而是被 ToolGateway 嵌进 tool response 里直接交付,见 part2.md §5.6.5。
 */
@Getter
@Builder
@ToString
public final class RollbackEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final String agentName;
    private final String toolName;
    private final int rolledCount;
    private final List<String> rolledTargets;

    @Override
    public String getType() {
        return "rollback";
    }
}

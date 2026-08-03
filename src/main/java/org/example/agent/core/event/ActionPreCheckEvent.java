package org.example.agent.core.event;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Map;

/**
 * 工具调用前触发，作为所有「调用前拦截」（policy / sandbox / 参数校验）
 * 的统一入口。AgentRuntimeImpl 在每条 tool_call 真实发出前 emit 本事件。
 *
 * 配套的 SandboxObserver 会订阅它，按 ToolRiskLevel 决定是否放行；
 * TokenBudgetObserver 也会在这里校验 budget 是否还有富余。
 */
@Getter
@Builder
@ToString
public final class ActionPreCheckEvent implements AgentEvent {

    private final String executionId;
    private final Instant at;
    private final int stepIndex;
    private final String agentName;
    private final String toolName;
    private final Map<String, Object> args;

    @Override
    public String getType() {
        return "action.pre_check";
    }
}

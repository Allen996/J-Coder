package org.example.agent.core.record;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.kind.StepKind;

import java.time.Instant;
import java.util.Map;

/**
 * 单步快照。AgentRuntime 在每一步（thought / action / observation）落盘一条。
 *
 * 用途：
 * - 重放器从 steps 重建整次执行
 * - 人工审核：把 thought 自然语言解释和 tool args 一起展示
 */
@Getter
@Builder
@ToString
public class StepRecord {

    /** 自增步号，从 1 开始。 */
    private final int stepIndex;

    /** 步骤类型：THOUGHT / ACTION / OBSERVATION。 */
    private final StepKind kind;

    /** 时间戳。 */
    private final Instant at;

    /** 关联的 agent 名（agent-platform 4.1 阶段只会有一个 agent，预留字段）。 */
    private final String agentName;

    /** 思考步骤的自然语言摘要（仅 THOUGHT 步骤有值）。 */
    private final String thoughtSummary;

    /** 调用的工具名（仅 ACTION 步骤）。 */
    private final String toolName;

    /** 工具调用参数（仅 ACTION 步骤）。敏感字段由 SandboxObserver 在 ActionPreCheck 阶段脱敏后落盘。 */
    private final Map<String, Object> toolArgs;

    /** 工具返回结果（仅 OBSERVATION 步骤）。 */
    private final String observationText;

    /** 本步耗时毫秒。 */
    private final long latencyMs;

    /** 本步 token 消耗（prompt + completion）。 */
    private final long tokensConsumed;
}

package org.example.agent.core.task.orchestrator;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;

/**
 * TaskOrchestrator 配置（part5 §8.7）。
 *
 * <p>集中放需要外部注入的可调参数；hardcoded 阈值（如 1.5× step budget）保留在 orchestrator 内。
 */
@Getter
@Builder
@ToString
public final class TaskOrchestratorConfig {

    /** VERIFY 连续失败 N 次后整个 plan abandoned。part5 §8.7 规则 4，默认 2。 */
    @Builder.Default
    private final int maxVerifyFailures = 2;

    /** 预算耗尽的重试放宽倍数。part5 §8.9 "重试时给一次性放宽的预算(1.5× step 上限)"。 */
    @Builder.Default
    private final double budgetRetriedMultiplier = 1.5;

    /** VERIFY 重试时是否放宽 step 上限。 */
    @Builder.Default
    private final boolean retryBudgetRelaxed = true;

    /** 项目根目录（用于 VerifyRunner 执行命令的工作目录）。 */
    private final Path projectRoot;

    /** 默认 VERIFY 子任务的 maxSteps 上限。VERIFY 通常纯命令执行，给大点。 */
    @Builder.Default
    private final int defaultVerifyMaxSteps = 8;

    /** 默认 IMPLEMENT 子任务的 maxSteps 上限。 */
    @Builder.Default
    private final int defaultImplementMaxSteps = 24;

    /** 默认 ANALYZE 子任务的 maxSteps 上限。 */
    @Builder.Default
    private final int defaultAnalyzeMaxSteps = 8;
}
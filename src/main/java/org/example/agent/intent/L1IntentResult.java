package org.example.agent.intent;

import java.util.List;
import java.util.Map;

/**
 * L1 粗意图分类的输出。设计稿 §3.2:严格 JSON,字段固定。
 *
 * <p>由 {@link LlmIntentClassifier} 产出 → {@link LocalIntentScorer} 融合关键词/槽位
 * 信号 → 形成最终 confidence。{@code primary} 与 {@code confidence} 都是融合后的结果。
 *
 * <p><b>方案 B 扩展</b>:新增 {@code appliedCalibrationRules} / {@code calibrationDiagnostics}
 * 两个字段,记录 {@link LlmConfidenceCalibrator} 实际触发的规则及每步 conf 变化。
 * 历史代码构造 L1IntentResult 时,这两个字段传 {@code List.of()} / {@code Map.of()} 即可。
 *
 * @param executionId   本次会话的 executionId(用于日志关联)
 * @param primary       融合后的主意图
 * @param confidence    融合后的最终置信度,0..1
 * @param candidates    Top-3 候选,用于"选项确认"档
 * @param slots         提取出的参数;填不出来置 null
 * @param negativeSignals 反向信号列表,如"不要 / 只是 / 先别"
 * @param modelRouteHint 路由建议
 * @param fallback      是否经过降级(解析失败 / 超时 → OFF_TOPIC)
 * @param fallbackReason 降级原因,可空
 * @param appliedCalibrationRules 校准器实际触发的规则名(空 list 表示未校准或 disabled)
 * @param calibrationDiagnostics  校准步骤诊断:{raw, after_high_clip, ..., final}
 */
public record L1IntentResult(
        String executionId,
        IntentLabel primary,
        double confidence,
        List<Candidate> candidates,
        Map<String, Object> slots,
        List<String> negativeSignals,
        ModelRouteHint modelRouteHint,
        boolean fallback,
        String fallbackReason,
        List<String> appliedCalibrationRules,
        Map<String, Double> calibrationDiagnostics
) {

    /**
     * 向后兼容构造器:9-arg 旧 API 仍可用,自动把校准字段填为空。
     */
    public L1IntentResult(String executionId,
                          IntentLabel primary,
                          double confidence,
                          List<Candidate> candidates,
                          Map<String, Object> slots,
                          List<String> negativeSignals,
                          ModelRouteHint modelRouteHint,
                          boolean fallback,
                          String fallbackReason) {
        this(executionId, primary, confidence, candidates, slots, negativeSignals,
                modelRouteHint, fallback, fallbackReason, List.of(), Map.of());
    }

    /** Top-K 候选(label + 融合前 score)。 */
    public record Candidate(IntentLabel label, double score) {}

    /** 设计稿 §3.3 三档:direct / offer / clarify。 */
    public enum Tier {
        /** conf ≥ 0.85 → 直接执行。 */
        DIRECT,
        /** 0.60 ≤ conf < 0.85 → 选项确认。 */
        OFFER,
        /** conf < 0.60 → 反问澄清。 */
        CLARIFY
    }

    public Tier tier(CliIntentProperties.L1Thresholds t) {
        if (t == null) return tier();
        if (confidence >= t.direct()) return Tier.DIRECT;
        if (confidence >= t.offer()) return Tier.OFFER;
        return Tier.CLARIFY;
    }

    /** 兜底:不传阈值时按设计稿默认 0.85 / 0.60 划分。 */
    public Tier tier() {
        if (confidence >= 0.85) return Tier.DIRECT;
        if (confidence >= 0.60) return Tier.OFFER;
        return Tier.CLARIFY;
    }
}
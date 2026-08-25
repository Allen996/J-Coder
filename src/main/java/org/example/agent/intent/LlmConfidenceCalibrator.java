package org.example.agent.intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 自评置信度校准器。<b>方案 B 第一阶段核心</b>。
 *
 * <p><b>动机</b>:LLM 给出的 {@code confidence} 存在几种已知病态:
 * <ul>
 *   <li><b>过度自信</b>:即使判断是"无证据兜底" (例 "你好" → CHAT_QA 0.87),LLM 也倾向于
 *       给一个看起来合理的分数,而不是说不确定。</li>
 *   <li><b>均匀偏高</b>:OFF_TOPIC 这种"边界模糊"类别上,LLM conf 几乎全部挤在 0.85-0.90。</li>
 *   <li><b>负向词压不住</b>: "do not run anything yet" 时 LLM 给 CHAT_QA 0.87,
 *       关键词层 negative_signals 已经检出,但 conf 没体现。</li>
 *   <li><b>类别边界模糊但强行给高分</b>: "提交一下" 这种纯动词词组,
 *       LLM 倾向判 OFF_TOPIC 0.76 但其实是 RUN_COMMAND。</li>
 * </ul>
 *
 * <p><b>设计原则</b>:不是替换 LLM,而是"事实核查员"——
 * LLM 是主分类器提候选,本类基于"多路信号交叉验证"对 conf 做后处理,使之落到可区分的区间。
 *
 * <p><b>4 条规则(按顺序执行)</b>:
 * <ol>
 *   <li>{@code HIGH_CLIP}: 永远生效。{@code c = min(raw, highClip)}。
 *       0.85+ 区间 LLM 区分度极差,统一压到上限。</li>
 *   <li>{@code NEGATIVE_OVERRIDE}: 当 {@code negativeSignals} 非空
 *       <b>且</b> primary ∈ {RUN_COMMAND, WRITE_PROJECT},
 *       {@code c = min(c, negOverrideCap)} (默认 0.40)。
 *       负向词命中后,执行类意图不应被高分放走。</li>
 *   <li>{@code CONFLICT_PENALTY}: 当 {@code llmPrimary != kwPrimary}
 *       <b>且</b> {@code raw > conflictRawThreshold},
 *       {@code c -= conflictDelta} (默认 0.25)。
 *       LLM 与关键词层 primary 不一致 + 高原始分,典型盲猜特征。</li>
 *   <li>{@code NO_EVIDENCE_CAP}: 当 {@code llmPrimary ∈ {OFF_TOPIC, CHAT_QA}}
 *       <b>且</b> {@code keywordHits} 为空
 *       <b>且</b> {@code raw > noEvidenceCap},
 *       {@code c = min(c, noEvidenceCap)} (默认 0.60)。
 *       无证据的高分兜底类意图,上限封顶。</li>
 * </ol>
 *
 * <p><b>下限</b>: {@code c = max(c, floor)} (默认 0.05),避免 score() 把 conf 算成 0 触发 fallback。
 *
 * <p><b>触发规则收集</b>: {@link CalibratedResult#appliedRules} 记录实际生效的规则名,便于离线分析
 * "是哪条规则把这条样本拉下来的"。{@link CalibratedResult#diagnostics} 记录每一步前后的 conf 值,
 * 便于画 calibration curve。
 *
 * <p><b>关闭</b>: {@code calibration.enabled = false} 时,calibrate() 直接返回 (raw, [], {raw})
 * 即不修改 LLM 原始 conf,等同于关掉本模块。
 */
public final class LlmConfidenceCalibrator {

    private static final String RULE_HIGH_CLIP = "HIGH_CLIP";
    private static final String RULE_NEG_OVERRIDE = "NEG_OVERRIDE";
    private static final String RULE_CONFLICT_PENALTY = "CONFLICT_PENALTY";
    private static final String RULE_NO_EVIDENCE_CAP = "NO_EVIDENCE_CAP";

    /** 触发 NEGATIVE_OVERRIDE 的类别集:负向词命中后,执行类意图不应被高分放走。 */
    private static final java.util.Set<IntentLabel> NEG_OVERRIDE_TARGETS =
            java.util.Set.of(IntentLabel.RUN_COMMAND, IntentLabel.WRITE_PROJECT);

    /** 触发 NO_EVIDENCE_CAP 的"通用兜底类":无关键词证据时上限封顶。 */
    private static final java.util.Set<IntentLabel> NO_EVIDENCE_TARGETS =
            java.util.Set.of(IntentLabel.OFF_TOPIC, IntentLabel.CHAT_QA);

    private final Calibration cfg;

    public LlmConfidenceCalibrator(Calibration cfg) {
        this.cfg = cfg;
    }

    /**
     * 校准入口。规则按类注释里的顺序执行,每条规则的触发和值变化都会被记录到 diagnostics。
     *
     * @param llmPrimary       LLM 输出的 primary label
     * @param rawLlmConf       LLM 输出的原始 conf ∈ [0, 1]
     * @param llmCandidates    LLM 输出的候选 label 列表(仅供冲突检测使用,本方法不改它)
     * @param kwPrimary        关键词层 primary label(null 表示无证据)
     * @param keywordHits      关键词层命中词列表(空表示无证据)
     * @param negativeSignals  负向词命中列表(非空触发 NEGATIVE_OVERRIDE)
     */
    public CalibratedResult calibrate(IntentLabel llmPrimary,
                                      double rawLlmConf,
                                      List<IntentLabel> llmCandidates,
                                      IntentLabel kwPrimary,
                                      List<String> keywordHits,
                                      List<String> negativeSignals) {
        if (!cfg.enabled()) {
            // 关闭状态:不修改原始 conf
            Map<String, Double> off = new LinkedHashMap<>();
            off.put("raw", rawLlmConf);
            return new CalibratedResult(rawLlmConf, List.of(), off);
        }
        if (llmPrimary == null) {
            Map<String, Double> nullDiag = new LinkedHashMap<>();
            nullDiag.put("raw", rawLlmConf);
            nullDiag.put("final", cfg.floor());
            return new CalibratedResult(cfg.floor(), List.of("FLOOR_NO_PRIMARY"), nullDiag);
        }

        List<String> applied = new ArrayList<>(4);
        Map<String, Double> diag = new LinkedHashMap<>();
        diag.put("raw", rawLlmConf);
        double c = rawLlmConf;

        // Rule 1: HIGH_CLIP
        if (c > cfg.highClip()) {
            c = cfg.highClip();
            applied.add(RULE_HIGH_CLIP);
        }
        diag.put("after_high_clip", c);

        // Rule 2: NEGATIVE_OVERRIDE
        boolean negTriggered = negativeSignals != null && !negativeSignals.isEmpty()
                && NEG_OVERRIDE_TARGETS.contains(llmPrimary);
        if (negTriggered && c > cfg.negOverrideCap()) {
            c = cfg.negOverrideCap();
            applied.add(RULE_NEG_OVERRIDE);
        }
        diag.put("after_neg_override", c);

        // Rule 3: CONFLICT_PENALTY
        // kwPrimary == null 视为"无关键词证据",不算冲突。
        boolean conflictTriggered = kwPrimary != null
                && llmPrimary != kwPrimary
                && rawLlmConf > cfg.conflictRawThreshold();
        if (conflictTriggered) {
            c -= cfg.conflictDelta();
            applied.add(RULE_CONFLICT_PENALTY);
        }
        diag.put("after_conflict_penalty", c);

        // Rule 4: NO_EVIDENCE_CAP
        boolean noEvTriggered = NO_EVIDENCE_TARGETS.contains(llmPrimary)
                && (keywordHits == null || keywordHits.isEmpty())
                && rawLlmConf > cfg.noEvidenceCap();
        if (noEvTriggered && c > cfg.noEvidenceCap()) {
            c = cfg.noEvidenceCap();
            applied.add(RULE_NO_EVIDENCE_CAP);
        }
        diag.put("after_no_evidence_cap", c);

        // Floor
        if (c < cfg.floor()) {
            c = cfg.floor();
        }
        diag.put("final", c);

        return new CalibratedResult(c, List.copyOf(applied), diag);
    }

    /**
     * 校准结果。
     *
     * @param value        校准后的 conf ∈ [floor, highClip]
     * @param appliedRules 实际生效的规则名列表(便于离线分析每条规则贡献)
     * @param diagnostics  逐步骤 conf 变化轨迹:{raw, after_high_clip, ..., final}
     */
    public record CalibratedResult(
            double value,
            List<String> appliedRules,
            Map<String, Double> diagnostics
    ) {}

    /**
     * 校准阈值配置。所有阈值都有默认值,调用方可仅覆盖需要调整的字段(<=0 即触发默认)。
     *
     * <p>默认值的选取理由:
     * <ul>
     *   <li>{@code highClip=0.85}: 与 {@code CliIntentProperties.L1Thresholds.direct=0.85} 对齐,
     *       保证 LLM 高分被压到 DIRECT 阈值附近,可观测。</li>
     *   <li>{@code negOverrideCap=0.40}: 低于 offer 阈值 0.60,
     *       负向词命中后应掉到 OFFER 以下甚至 CLARIFY。</li>
     *   <li>{@code conflictDelta=0.25}: 经验值,与默认 penConflict=0.15 同量级,
     *       保证冲突扣分后还能落在有效区间。</li>
     *   <li>{@code conflictRawThreshold=0.70}: 0.70 以上视为"高 raw 的盲猜",以下不算冲突。</li>
     *   <li>{@code noEvidenceCap=0.60}: 等于 offer 阈值,无证据的兜底类不超过 offer。</li>
     *   <li>{@code floor=0.05}: 避免 score 算出 0 触发 fallback。</li>
     * </ul>
     */
    public record Calibration(
            boolean enabled,
            double highClip,
            double negOverrideCap,
            double conflictDelta,
            double conflictRawThreshold,
            double noEvidenceCap,
            double floor
    ) {
        public Calibration {
            if (highClip <= 0)              highClip = 0.85;
            if (negOverrideCap <= 0)        negOverrideCap = 0.40;
            if (conflictDelta <= 0)         conflictDelta = 0.25;
            if (conflictRawThreshold <= 0)  conflictRawThreshold = 0.70;
            if (noEvidenceCap <= 0)         noEvidenceCap = 0.60;
            if (floor <= 0)                 floor = 0.05;
        }
    }
}
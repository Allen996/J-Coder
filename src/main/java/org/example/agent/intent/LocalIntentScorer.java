package org.example.agent.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合打分器。设计稿 §5.3:
 *
 * <pre>
 * final_conf = w_llm * llm_conf
 *            + w_keyword * keyword_match_score
 *            + w_slot * slot_completeness_score
 *            - pen_conflict if (rule_signal 强冲突 LLM 选择) else 0
 *            - pen_negative if negative_signals 非空 else 0
 * </pre>
 *
 * <p><b>方案 B 调整</b>:
 * <ul>
 *   <li>{@code score()} 多收一个 {@code llmConfOverride} 参数,由 {@code IntentGate}
 *       把 {@link LlmConfidenceCalibrator} 校准后的 conf 传进来。
 *       {@code <0} 表示使用 LLM 原始 conf (历史行为)。</li>
 *   <li>{@code slotScore} <b>退出加权</b>(wSlot 不再乘进 final_conf),改作观测字段。
 *       slot 完整性不再影响分类置信度,而是改为由 {@code SlotCompletenessValidator}
 *       作为 tier 准入门槛判定(在 IntentGate 里做)。</li>
 *   <li>{@code finalConf} 仍归一化到 [0,1]:扣除 wSlot 后剩余权重和 = wLlm + wKeyword,
 *       用 (wLlm + wKeyword) 做归一化,使新旧公式在同一区间可比。</li>
 * </ul>
 *
 * <p><b>权重从 {@link CliIntentProperties.Scoring} 读取</b>(默认 0.6 / 0.2 / 0.2 / 0.15 / 0.10,
 * 保持历史行为);所有调整通过 {@code application.yml} 的 {@code cli.intent.l1.scoring} 节点,
 * 在 {@code eval/intent/golden.jsonl} 的 train 上做网格搜索,dev 选超参,test 只报一次。
 *
 * <h2>可观测的中间信号</h2>
 *
 * <p>{@link Scored} 现在额外带 {@code llmConf} / {@code keywordScore} / {@code slotScore} /
 * {@code conflict} / {@code negative} / {@code scoringWeights},方便在 {@code /intent-stats}
 * 与 eval 报告中看到每条数据的真实来源,便于诊断"为什么这条被分到 X"。
 *
 * <p>纯规则实现,首期够用(设计稿 §12 待决项 1:不用本地 ML 模型)。
 */
@Component
public class LocalIntentScorer {

    private final CliIntentProperties properties;

    public LocalIntentScorer(CliIntentProperties properties) {
        this.properties = properties;
    }

    /** 便捷构造器(供不通过 Spring 的单元测试使用默认权重)。 */
    public LocalIntentScorer() {
        this(new CliIntentProperties());
    }

    private CliIntentProperties.Scoring w() {
        return properties == null || properties.l1() == null || properties.l1().scoring() == null
                ? new CliIntentProperties.Scoring(0.6, 0.2, 0.2, 0.15, 0.10)
                : properties.l1().scoring();
    }

    /**
     * 历史入口:不传 calibrated conf(llmConfOverride < 0),走原始 LLM conf。
     * 保留 4-arg 形式给不调用 calibrator 的旧测试/工具使用。
     */
    public Scored score(LlmIntentClassifier.Outcome llm,
                        IntentSignalExtractor.SignalResult signal,
                        SlotCompletenessChecker slots,
                        String userInput) {
        return score(llm, signal, slots, userInput, -1.0);
    }

    /**
     * 主入口。{@code llmConfOverride} ∈ [0,1] 表示校准后的 LLM conf(<0 表示用 LLM 原始 conf)。
     * 方案 B:IntentGate 在调用本方法前已经把 calibrator 跑过,把 calibrated.value() 传进来。
     */
    public Scored score(LlmIntentClassifier.Outcome llm,
                        IntentSignalExtractor.SignalResult signal,
                        SlotCompletenessChecker slots,
                        String userInput,
                        double llmConfOverride) {

        if (llm == null || llm.degraded() || llm.primary() == null) {
            // 整次分类视作失败 —— 直接走降级路径。
            // 方案 B:conf=0.0(不是 0.5),让 tier 自然落到 CLARIFY(< 0.60),
            // 并在评测器里作为"未决策"被排除。
            // 历史值 0.5 会让 defaultLabel 的兜底算成 true positive,
            // 反而在 LLM 超时场景下污染召回率统计。
            // 第三阶段:defaultLabel 由 OFF_TOPIC 改为 CHAT_QA。
            String reason = llm == null
                    ? "llm null"
                    : (llm.reason() == null || llm.reason().isBlank() ? "llm degraded" : llm.reason());
            IntentLabel def = IntentLabel.CHAT_QA;
            return new Scored(def, 0.0,
                    List.of(new L1IntentResult.Candidate(def, 0.0)),
                    true, reason, 0.0, 0.5, 0.5, false, false, w());
        }

        IntentLabel label = llm.primary();
        // 方案 B:优先使用 calibrated conf;<0 fallback 到原始 LLM conf
        double llmConf = (llmConfOverride >= 0)
                ? clamp(llmConfOverride)
                : clamp(llm.confidence());
        double keywordScore = signal == null ? 0.5 : clamp(signal.keywordMatchScore());
        // 方案 B:slot 退出加权 —— 仍计算 completeness 作为观测,但不进入 final_conf
        double slotScore = slots == null ? 0.5 : clamp(slots.completeness(label, llm.slots(), userInput));

        boolean conflict = signal != null
                && signal.suggestedLabel() != null
                && signal.suggestedLabel() != label
                && isWriteReadConflict(signal.suggestedLabel(), label);
        boolean negative = signal != null && signal.negativeSignals() != null && !signal.negativeSignals().isEmpty();

        CliIntentProperties.Scoring sc = w();
        // 方案 B:删除 wSlot 项,只用 wLlm + wKeyword 加权,然后归一化到 [0,1]
        double weightedSum = sc.wLlm() * llmConf
                + sc.wKeyword() * keywordScore
                - (conflict ? sc.penConflict() : 0.0)
                - (negative ? sc.penNegative() : 0.0);
        double weightTotal = sc.wLlm() + sc.wKeyword(); // = 0.8 在默认配置下
        double finalConf = (weightTotal > 0) ? clamp(weightedSum / weightTotal) : clamp(weightedSum);

        List<L1IntentResult.Candidate> candidates = mergeCandidates(llm, signal);
        if (candidates.isEmpty()) {
            candidates = List.of(new L1IntentResult.Candidate(label, finalConf));
        }
        return new Scored(label, finalConf, candidates, false, null,
                llmConf, keywordScore, slotScore, conflict, negative, sc);
    }

    /**
     * 把 LLM 的 candidates 与信号层最强烈候选合并,按 score 降序取 Top-3。
     * 同一 label 重复时取较大 score。
     */
    private List<L1IntentResult.Candidate> mergeCandidates(LlmIntentClassifier.Outcome llm,
                                                           IntentSignalExtractor.SignalResult signal) {
        Map<IntentLabel, Double> merged = new HashMap<>();
        if (llm.candidates() != null) {
            for (L1IntentResult.Candidate c : llm.candidates()) {
                merged.put(c.label(), Math.max(merged.getOrDefault(c.label(), 0.0), c.score()));
            }
        }
        if (signal != null && signal.suggestedLabel() != null) {
            double sigScore = signal.keywordMatchScore();
            merged.put(signal.suggestedLabel(),
                    Math.max(merged.getOrDefault(signal.suggestedLabel(), 0.0), sigScore));
        }
        List<Map.Entry<IntentLabel, Double>> sorted = new ArrayList<>(merged.entrySet());
        sorted.sort(Comparator.<Map.Entry<IntentLabel, Double>>comparingDouble(Map.Entry::getValue).reversed());
        List<L1IntentResult.Candidate> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            Map.Entry<IntentLabel, Double> e = sorted.get(i);
            top.add(new L1IntentResult.Candidate(e.getKey(), clamp(e.getValue())));
        }
        return top;
    }

    /** WRITE/READ 二分时算"强冲突",其他组合(CHAT_QA 与写读)不算冲突。 */
    private boolean isWriteReadConflict(IntentLabel rule, IntentLabel llm) {
        if (rule == null || llm == null) return false;
        boolean ruleWrite = rule == IntentLabel.WRITE_PROJECT;
        boolean ruleRead = rule == IntentLabel.READ_CODE;
        boolean llmWrite = llm == IntentLabel.WRITE_PROJECT;
        boolean llmRead = llm == IntentLabel.READ_CODE;
        return (ruleWrite && llmRead) || (ruleRead && llmWrite);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    /**
     * 打分结果 + 中间信号,供 eval 报告与 /intent-stats 消费。
     * 原有字段(primary/confidence/candidates/fallback/fallbackReason)保持兼容。
     */
    public record Scored(
            IntentLabel primary,
            double confidence,
            List<L1IntentResult.Candidate> candidates,
            boolean fallback,
            String fallbackReason,
            /** 原始 LLM 自评置信度;fallback 时为 0.5。 */
            double llmConf,
            /** 关键词层打分;无信号时为 0.5。 */
            double keywordScore,
            /** 槽位完整度;无信号时为 0.5。 */
            double slotScore,
            /** 是否触发 WRITE/READ 强冲突扣分。 */
            boolean conflict,
            /** 是否触发负向信号扣分。 */
            boolean negative,
            /** 本次打分实际使用的权重(可观测,避免 debug 时改 yml 看不到效果)。 */
            CliIntentProperties.Scoring scoringWeights
    ) {
        /** 兼容旧调用方(不传中间信号)。 */
        public Scored(IntentLabel primary, double confidence,
                      List<L1IntentResult.Candidate> candidates,
                      boolean fallback, String fallbackReason) {
            this(primary, confidence, candidates, fallback, fallbackReason,
                    0.5, 0.5, 0.5, false, false,
                    new CliIntentProperties.Scoring(0.6, 0.2, 0.2, 0.15, 0.10));
        }
    }
}
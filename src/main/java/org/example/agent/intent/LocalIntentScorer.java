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
 * final_conf = 0.6 * llm_conf
 *            + 0.2 * keyword_match_score
 *            + 0.2 * slot_completeness_score
 *            - 0.15 if (rule_signal 强冲突 LLM 选择) else 0
 *            - 0.10 if negative_signals 非空 else 0
 * </pre>
 *
 * <p>纯规则实现,首期够用(设计稿 §12 待决项 1:不用本地 ML 模型)。
 *
 * <h2>权重与扣分项的取值依据</h2>
 *
 * <p>设计稿 §5 引子明确写"LLM 倾向于高置信度,不要直接相信 LLM 给出的数字",
 * 但 LLM 又是上下文理解最丰富、信号最贵的来源。所以权重需要同时满足两条约束:
 *
 * <ol>
 *   <li><b>LLM 主导但不独断</b>:给 LLM 过半的权重(60%)是承认它的信号质量,
 *       但留出 40% 给廉价结构化信号做修正——这样 LLM 严重错判时,
 *       规则+槽位有足够力量把 final_conf 拉离 direct 档。</li>
 *   <li><b>规则+槽位 = 0.4,与 LLM 形成 6:4 杠杆</b>:关键词与槽位都是确定性的
 *       0/1 区间信号,可独立校准,各占 20%,合计 40%。三段权重和恰为 1.0,
 *       在无任何扣分时,final_conf 自然落在 [0, 1] 区间,clamp 兜底。</li>
 * </ol>
 *
 * <h3>冲突扣分 0.15</h3>
 *
 * <p>WRITE/READ 强冲突(规则强信号指向 READ,LLM 选 WRITE,或反过来)
 * 是最危险的误判:用户嘴上说"看",模型理解成"改",会直接触发写工具。
 * 0.15 这个值是按"direct→offer 临界"反推的:
 *
 * <ul>
 *   <li>理想情况 LLM 0.95 + 规则 1.0 + 槽位 1.0:0.6·0.95 + 0.2·1.0 + 0.2·1.0 = 0.97</li>
 *   <li>扣 0.15 → 0.82,正好从 direct(≥0.85)落到 offer(0.60–0.85)档——
 *       设计想要的"在直接执行和反问之间再让用户确认一次"</li>
 *   <li>又不至于跌破 0.60 进入反问档,避免一次轻量查询被反复打扰</li>
 * </ul>
 *
 * <h3>负向扣分 0.10</h3>
 *
 * <p>negative_signals("别/不要/先别/just/only")含义是"用户已经主动声明了边界",
 * 这时 LLM 即使选了 WRITE 也可能确实是想写,只是要"先别"。
 * 0.10 故意比冲突扣分轻:
 *
 * <ul>
 *   <li>足以把一个本来 direct 的请求降一档到 offer(让用户口头 ack 一下意图)</li>
 *   <li>不至于到反问档(用户已经表达得够清楚了,继续问是骚扰)</li>
 * </ul>
 *
 * <h3>校准锚点(对应 {@code LocalIntentScorerTest})</h3>
 *
 * <ul>
 *   <li>highConfidencePath:0.6·0.95 + 0.2·0.9 + 0.2·1.0 = 0.95, &gt; 0.85 ✓ direct</li>
 *   <li>ruleConflictPenalty:0.6·0.9 + 0.2·0.9 + 0.2·1.0 − 0.15 = 0.77, &lt; 0.85 ✓ offer</li>
 *   <li>negativeSignalPenalty:0.6·0.9 + 0.2·0.9 + 0.2·1.0 − 0.10 = 0.82, &lt; 0.9 ✓</li>
 * </ul>
 *
 * <p>调整建议:这些数字目前来自经验调参,不是网格搜索或回归拟合。
 * 真实使用中应通过 {@code /intent-stats} 收集 final_conf 与用户最终选择(label)
 * 的对齐率,反推最优权重——本类暂不引入自适应学习,首期目标是"可解释、可回滚"。
 */
@Component
public class LocalIntentScorer {

    private static final double W_LLM = 0.6;
    private static final double W_KEYWORD = 0.2;
    private static final double W_SLOT = 0.2;
    private static final double PEN_CONFLICT = 0.15;
    private static final double PEN_NEGATIVE = 0.10;

    public Scored score(LlmIntentClassifier.Outcome llm,
                        IntentSignalExtractor.SignalResult signal,
                        SlotCompletenessChecker slots,
                        String userInput) {

        if (llm == null || llm.degraded() || llm.primary() == null) {
            // 整次分类视作失败 —— 直接走降级路径,这里产出 OFF_TOPIC / 0.5
            String reason = llm == null
                    ? "llm null"
                    : (llm.reason() == null || llm.reason().isBlank() ? "llm degraded" : llm.reason());
            return new Scored(IntentLabel.OFF_TOPIC, 0.5,
                    List.of(new L1IntentResult.Candidate(IntentLabel.OFF_TOPIC, 0.5)),
                    true, reason);
        }

        IntentLabel label = llm.primary();
        double llmConf = clamp(llm.confidence());
        double keywordScore = signal == null ? 0.5 : clamp(signal.keywordMatchScore());
        double slotScore = slots == null ? 0.5 : clamp(slots.completeness(label, llm.slots(), userInput));

        boolean conflict = signal != null
                && signal.suggestedLabel() != null
                && signal.suggestedLabel() != label
                && isWriteReadConflict(signal.suggestedLabel(), label);
        boolean negative = signal != null && signal.negativeSignals() != null && !signal.negativeSignals().isEmpty();

        double finalConf = W_LLM * llmConf
                + W_KEYWORD * keywordScore
                + W_SLOT * slotScore
                - (conflict ? PEN_CONFLICT : 0.0)
                - (negative ? PEN_NEGATIVE : 0.0);
        finalConf = clamp(finalConf);

        List<L1IntentResult.Candidate> candidates = mergeCandidates(llm, signal);
        if (candidates.isEmpty()) {
            candidates = List.of(new L1IntentResult.Candidate(label, finalConf));
        }
        return new Scored(label, finalConf, candidates, false, null);
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

    /** WRITE/READ 二分时算"强冲突",其他组合(CHAT_QA / OFF_TOPIC 与写读)不算冲突。 */
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

    public record Scored(
            IntentLabel primary,
            double confidence,
            List<L1IntentResult.Candidate> candidates,
            boolean fallback,
            String fallbackReason
    ) {}
}
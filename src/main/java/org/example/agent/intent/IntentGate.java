package org.example.agent.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * L1 编排器。把 LlmIntentClassifier / KeywordSignalExtractor /
 * SlotCompletenessChecker / LocalIntentScorer / IntentPrompter 串成一条
 * "分类 → 打分 → 降级 → 必要时弹 UI" 的流水线。
 *
 * <p>输出 {@link IntentContext} 供 ReActLoop 在整个 loop 期间使用。
 */
@Component
public class IntentGate {

    private static final Logger log = LoggerFactory.getLogger(IntentGate.class);

    private final LlmIntentClassifier llm;
    private final IntentSignalExtractor signals;
    private final SlotCompletenessChecker slots;
    private final SlotCompletenessValidator slotValidator; // 方案 B 新增
    private final LlmConfidenceCalibrator calibrator;       // 方案 B 新增
    private final LocalIntentScorer scorer;
    private final IntentPrompter prompter;
    private final IntentFallbackPolicy fallbackPolicy;
    private final CliIntentProperties properties;
    private final StrongPatternClassifier strongPatterns;   // 第三阶段新增
    private final String lightModel;
    private final String codeModel;
    private final String generalModel;
    private final List<IntentLogSink> logSinks;

    /** 最近若干次 L1 结果的 in-memory 副本,供 /intent-stats 消费。 */
    private final RecentResults recentResults = new RecentResults();

    public IntentGate(LlmIntentClassifier llm,
                      IntentSignalExtractor signals,
                      SlotCompletenessChecker slots,
                      SlotCompletenessValidator slotValidator,
                      LlmConfidenceCalibrator calibrator,
                      LocalIntentScorer scorer,
                      IntentPrompter prompter,
                      IntentFallbackPolicy fallbackPolicy,
                      CliIntentProperties properties,
                      StrongPatternClassifier strongPatterns,
                      @Value("${cli.intent.model-routing.light:qwen3.7-flash}") String lightModel,
                      @Value("${cli.intent.model-routing.code:qwen3.7-plus}") String codeModel,
                      @Value("${cli.intent.model-routing.general:qwen3.7-plus}") String generalModel,
                      List<IntentLogSink> logSinks) {
        this.llm = llm;
        this.signals = signals;
        this.slots = slots;
        this.slotValidator = slotValidator == null ? new SlotCompletenessValidator() : slotValidator;
        this.calibrator = calibrator == null
                ? new LlmConfidenceCalibrator(new LlmConfidenceCalibrator.Calibration(false, 0, 0, 0, 0, 0, 0))
                : calibrator;
        this.scorer = scorer;
        this.prompter = prompter;
        this.fallbackPolicy = fallbackPolicy;
        this.properties = properties;
        this.strongPatterns = strongPatterns == null ? new StrongPatternClassifier() : strongPatterns;
        this.lightModel = lightModel;
        this.codeModel = codeModel;
        this.generalModel = generalModel;
        this.logSinks = logSinks == null ? List.of() : logSinks;
    }

    /**
     * 对单条用户输入做一次完整 L1 流程。
     *
     * <p>失败/降级/选择跳过都会走 fallbackPolicy,产出可继续 loop 的 IntentContext。
     */
    public IntentContext classify(String executionId, String userInput) {
        if (!fallbackPolicy.l1Enabled() || userInput == null || userInput.isBlank()) {
            L1IntentResult fallback = fallbackPolicy.buildFallback(executionId,
                    userInput == null || userInput.isBlank() ? "empty input" : "l1 disabled");
            return buildContext(fallback);
        }
        // 1) LLM 自评
        LlmIntentClassifier.Outcome llmOut = llm.classify(executionId, userInput);

        // 2) 关键词 + 槽位 信号
        IntentSignalExtractor.SignalResult sig = signals.extract(userInput);

        // 2.5) 方案 B:LLM conf 校准 —— 基于多路信号交叉验证压低盲猜
        List<IntentLabel> llmCandidateLabels = llmOut.candidates() == null
                ? List.of()
                : llmOut.candidates().stream()
                        .map(L1IntentResult.Candidate::label)
                        .toList();
        LlmConfidenceCalibrator.CalibratedResult calibrated = calibrator.calibrate(
                llmOut.primary(),
                llmOut.confidence(),
                llmCandidateLabels,
                sig.suggestedLabel(),
                sig.keywordHits(),
                sig.negativeSignals());

        // 3) 融合打分 —— 把校准后的 llmConf 传给 scorer,避免 scorer 自己再校一遍
        LocalIntentScorer.Scored scored = scorer.score(llmOut, sig, slots, userInput,
                calibrated.value());
        L1IntentResult result = new L1IntentResult(
                executionId,
                scored.primary(),
                scored.confidence(),
                scored.candidates(),
                llmOut.slots() == null ? java.util.Map.of() : llmOut.slots(),
                llmOut.negativeSignals() == null ? sig.negativeSignals() : llmOut.negativeSignals(),
                llmOut.modelRouteHint(),
                scored.fallback(),
                scored.fallbackReason(),
                calibrated.appliedRules(),
                calibrated.diagnostics());

        // 3.5) 方案 B:slot 准入门槛 —— WRITE/RUN 缺必填槽 → conf cap 到 offer 以下 → 强制 OFFER
        SlotCompletenessValidator.ValidationResult slotVR =
                slotValidator.validate(result.primary(), result.slots());
        boolean tierPinnedBySlot = !slotVR.allPassed()
                && (result.primary() == IntentLabel.WRITE_PROJECT
                        || result.primary() == IntentLabel.RUN_COMMAND);
        if (tierPinnedBySlot && result.confidence() >= properties.l1().thresholds().offer()) {
            double capped = Math.max(properties.l1().thresholds().offer() - 0.01,
                    Math.min(result.confidence(), 0.59));
            result = new L1IntentResult(
                    result.executionId(), result.primary(), capped,
                    result.candidates(), result.slots(), result.negativeSignals(),
                    result.modelRouteHint(), result.fallback(), result.fallbackReason(),
                    result.appliedCalibrationRules(), result.calibrationDiagnostics());
        }

        // 4) 三档决策
        // 4.0) 第三阶段:强 pattern 触发 → 强制 CLARIFY(模板化反问,不递归 classifyFresh)
        //      触发条件:StrongPatternClassifier 命中任意 pattern
        //              && LLM primary ∈ {READ_CODE, WRITE_PROJECT, RUN_COMMAND, PLANNING}
        //              (不强制"关键词层无编程意图"——见设计说明,简化 Q3-A)
        boolean strongPatternTriggered = false;
        StrongPatternClassifier.ClassificationResult sp = strongPatterns.classify(userInput);
        List<StrongPatternClassifier.PatternType> spTypes = sp.matchedTypes().stream()
                .sorted()
                .toList();
        if (!sp.empty() && llmOut.primary() != null) {
            IntentLabel p = llmOut.primary();
            if (p == IntentLabel.READ_CODE || p == IntentLabel.WRITE_PROJECT
                    || p == IntentLabel.RUN_COMMAND || p == IntentLabel.PLANNING) {
                strongPatternTriggered = true;
            }
        }

        L1IntentResult.Tier tier = strongPatternTriggered
                ? L1IntentResult.Tier.CLARIFY
                : result.tier(properties.l1().thresholds());
        IntentLabel chosen = result.primary();
        // 反问文本(强 pattern 触发时生成一次,供 attach 到 ctx;suppress=true 也生成)
        String strongPatternClarifyText = null;
        switch (tier) {
            case DIRECT -> { /* 选 primary */ }
            case OFFER -> chosen = prompter.promptOffer(userInput, result);
            case CLARIFY -> {
                if (strongPatternTriggered) {
                    // 第三阶段:不走原有 promptClarify(它读 stdin),
                    // 也不递归 classifyFresh(避免被反问文本再次触发强 pattern 形成死循环)。
                    // 由 prompter 模板化生成反问文本,挂到 IntentContext 供 eval / 日志观测。
                    strongPatternClarifyText = prompter.promptStrongPatternClarify(
                            userInput, result.primary(), spTypes);
                    // 保留原 conf(决策 B:不强制 cap 到 0.59,保留 LLM 原始信号)
                    // chosen 不改 —— primary 仍是 LLM 判的写读类
                } else {
                    String clarified = prompter.promptClarify(userInput, result);
                    if (clarified != null && !clarified.isBlank() && !clarified.equals(userInput)) {
                        L1IntentResult secondRound = classifyFresh(executionId, clarified);
                        if (secondRound != null) {
                            result = secondRound;
                            chosen = result.primary();
                        }
                    }
                }
            }
        }

        L1IntentResult finalResult = chosen == result.primary()
                ? result
                : new L1IntentResult(result.executionId(), chosen,
                        Math.max(result.confidence(), 0.85), result.candidates(),
                        result.slots(), result.negativeSignals(),
                        result.modelRouteHint(), result.fallback(), result.fallbackReason(),
                        result.appliedCalibrationRules(), result.calibrationDiagnostics());

        // 5) 解析模型路由 → 实际 model name
        String resolvedModel = resolveModelName(finalResult.modelRouteHint());
        IntentContext ctx = new IntentContext(finalResult, resolvedModel);
        ctx.setDecidedTier(tier); // 第三阶段:把决策后的 tier 挂到 ctx(强 pattern 强制改写时尤其重要)
        if (strongPatternTriggered) {
            List<String> typeNames = spTypes.stream().map(Enum::name).toList();
            ctx.attachStrongPattern(typeNames, strongPatternClarifyText);
        }

        recordLog(executionId, userInput, llmOut, sig, finalResult, ctx);
        return ctx;
    }

    /** 显式跳过 LLM,纯规则 + 槽位的快速路径(供 L2 上下文回看)。 */
    public IntentContext quickFallback(String executionId, String userInput, String reason) {
        return buildContext(fallbackPolicy.buildFallback(executionId, reason));
    }

    private L1IntentResult classifyFresh(String executionId, String userInput) {
        LlmIntentClassifier.Outcome llmOut = llm.classify(executionId, userInput);
        IntentSignalExtractor.SignalResult sig = signals.extract(userInput);
        List<IntentLabel> llmCandidateLabels = llmOut.candidates() == null
                ? List.of()
                : llmOut.candidates().stream()
                        .map(L1IntentResult.Candidate::label)
                        .toList();
        LlmConfidenceCalibrator.CalibratedResult calibrated = calibrator.calibrate(
                llmOut.primary(),
                llmOut.confidence(),
                llmCandidateLabels,
                sig.suggestedLabel(),
                sig.keywordHits(),
                sig.negativeSignals());
        LocalIntentScorer.Scored scored = scorer.score(llmOut, sig, slots, userInput,
                calibrated.value());
        L1IntentResult result = new L1IntentResult(
                executionId,
                scored.primary(),
                scored.confidence(),
                scored.candidates(),
                llmOut.slots() == null ? java.util.Map.of() : llmOut.slots(),
                llmOut.negativeSignals() == null ? sig.negativeSignals() : llmOut.negativeSignals(),
                llmOut.modelRouteHint(),
                scored.fallback(),
                scored.fallbackReason(),
                calibrated.appliedRules(),
                calibrated.diagnostics());
        // slot 准入门槛
        SlotCompletenessValidator.ValidationResult slotVR =
                slotValidator.validate(result.primary(), result.slots());
        if (!slotVR.allPassed()
                && (result.primary() == IntentLabel.WRITE_PROJECT
                        || result.primary() == IntentLabel.RUN_COMMAND)
                && result.confidence() >= properties.l1().thresholds().offer()) {
            double capped = Math.max(properties.l1().thresholds().offer() - 0.01,
                    Math.min(result.confidence(), 0.59));
            result = new L1IntentResult(
                    result.executionId(), result.primary(), capped,
                    result.candidates(), result.slots(), result.negativeSignals(),
                    result.modelRouteHint(), result.fallback(), result.fallbackReason(),
                    result.appliedCalibrationRules(), result.calibrationDiagnostics());
        }
        return result;
    }

    private String resolveModelName(ModelRouteHint hint) {
        if (hint == null) return generalModel;
        return switch (hint) {
            case LIGHT -> lightModel;
            case CODE -> codeModel;
            case GENERAL -> generalModel;
        };
    }

    private IntentContext buildContext(L1IntentResult result) {
        return new IntentContext(result, resolveModelName(result.modelRouteHint()));
    }

    private void recordLog(String executionId, String userInput,
                           LlmIntentClassifier.Outcome llmOut,
                           IntentSignalExtractor.SignalResult sig,
                           L1IntentResult finalResult,
                           IntentContext ctx) {
        recentResults.add(finalResult);
        IntentLogEvent event = new IntentLogEvent(
                executionId, System.currentTimeMillis(), userInput,
                llmOut.primary() == null ? null : llmOut.primary().name(),
                llmOut.confidence(),
                sig == null ? null : sig.suggestedLabel() == null ? null : sig.suggestedLabel().name(),
                sig == null ? 0.5 : sig.keywordMatchScore(),
                sig == null ? List.of() : sig.negativeSignals(),
                finalResult.primary().name(),
                finalResult.confidence(),
                // 第三阶段:强 pattern 触发时 tier 强制改写,recordLog 也要反映
                ctx.isClarifiedByStrongPattern() ? "CLARIFY" :
                        finalResult.tier(properties.l1().thresholds()).name(),
                finalResult.modelRouteHint().name(),
                ctx.resolvedModel(),
                finalResult.fallback(),
                finalResult.fallbackReason(),
                ctx.strongPatternTypes(),
                ctx.clarifyText());
        for (IntentLogSink sink : logSinks) {
            try { sink.onL1(event); } catch (Exception ex) { log.warn("intent log sink failed: {}", ex.getMessage()); }
        }
    }

    /** 给 /intent-stats 用:最近若干次 L1 结果的副本。 */
    public List<L1IntentResult> recentResults() {
        return recentResults.snapshot();
    }

    public IntentPrompter prompter() {
        return prompter;
    }

    /** 一个 64 条上限的 FIFO,不需要外部依赖。 */
    private static final class RecentResults {
        private final ArrayDeque<L1IntentResult> q = new ArrayDeque<>(64);
        private static final int CAPACITY = 64;
        synchronized void add(L1IntentResult r) {
            q.addLast(r);
            while (q.size() > CAPACITY) q.removeFirst();
        }
        synchronized List<L1IntentResult> snapshot() { return new ArrayList<>(q); }
    }

    /** L1 日志事件。 */
    public record IntentLogEvent(
            String executionId, long timestamp, String userInput,
            String llmLabel, double llmConf,
            String ruleSuggested, double keywordScore,
            List<String> negativeSignals,
            String finalLabel, double finalConfidence,
            String tier, String modelRouteHint, String resolvedModel,
            boolean fallback, String fallbackReason,
            // 第三阶段:强 pattern 触发标记 + 反问文本
            List<String> strongPatternTypes,
            String clarifyText
    ) {}
}
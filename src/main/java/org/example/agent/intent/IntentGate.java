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
    private final LocalIntentScorer scorer;
    private final IntentPrompter prompter;
    private final IntentFallbackPolicy fallbackPolicy;
    private final CliIntentProperties properties;
    private final String lightModel;
    private final String codeModel;
    private final String generalModel;
    private final List<IntentLogSink> logSinks;

    /** 最近若干次 L1 结果的 in-memory 副本,供 /intent-stats 消费。 */
    private final RecentResults recentResults = new RecentResults();

    public IntentGate(LlmIntentClassifier llm,
                      IntentSignalExtractor signals,
                      SlotCompletenessChecker slots,
                      LocalIntentScorer scorer,
                      IntentPrompter prompter,
                      IntentFallbackPolicy fallbackPolicy,
                      CliIntentProperties properties,
                      @Value("${cli.intent.model-routing.light:qwen3.7-flash}") String lightModel,
                      @Value("${cli.intent.model-routing.code:qwen3.7-plus}") String codeModel,
                      @Value("${cli.intent.model-routing.general:qwen3.7-plus}") String generalModel,
                      List<IntentLogSink> logSinks) {
        this.llm = llm;
        this.signals = signals;
        this.slots = slots;
        this.scorer = scorer;
        this.prompter = prompter;
        this.fallbackPolicy = fallbackPolicy;
        this.properties = properties;
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

        // 3) 融合打分
        LocalIntentScorer.Scored scored = scorer.score(llmOut, sig, slots, userInput);
        L1IntentResult result = new L1IntentResult(
                executionId,
                scored.primary(),
                scored.confidence(),
                scored.candidates(),
                llmOut.slots() == null ? java.util.Map.of() : llmOut.slots(),
                llmOut.negativeSignals() == null ? sig.negativeSignals() : llmOut.negativeSignals(),
                llmOut.modelRouteHint(),
                scored.fallback(),
                scored.fallbackReason());

        // 4) 三档决策
        L1IntentResult.Tier tier = result.tier(properties.l1().thresholds());
        IntentLabel chosen = result.primary();
        switch (tier) {
            case DIRECT -> { /* 选 primary */ }
            case OFFER -> chosen = prompter.promptOffer(userInput, result);
            case CLARIFY -> {
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

        L1IntentResult finalResult = chosen == result.primary()
                ? result
                : new L1IntentResult(result.executionId(), chosen,
                        Math.max(result.confidence(), 0.85), result.candidates(),
                        result.slots(), result.negativeSignals(),
                        result.modelRouteHint(), result.fallback(), result.fallbackReason());

        // 5) 解析模型路由 → 实际 model name
        String resolvedModel = resolveModelName(finalResult.modelRouteHint());
        IntentContext ctx = new IntentContext(finalResult, resolvedModel);

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
        LocalIntentScorer.Scored scored = scorer.score(llmOut, sig, slots, userInput);
        return new L1IntentResult(
                executionId,
                scored.primary(),
                scored.confidence(),
                scored.candidates(),
                llmOut.slots() == null ? java.util.Map.of() : llmOut.slots(),
                llmOut.negativeSignals() == null ? sig.negativeSignals() : llmOut.negativeSignals(),
                llmOut.modelRouteHint(),
                scored.fallback(),
                scored.fallbackReason());
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
                finalResult.tier(properties.l1().thresholds()).name(),
                finalResult.modelRouteHint().name(),
                ctx.resolvedModel(),
                finalResult.fallback(),
                finalResult.fallbackReason());
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
            boolean fallback, String fallbackReason
    ) {}
}
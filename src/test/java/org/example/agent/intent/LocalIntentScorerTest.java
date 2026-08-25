package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalIntentScorerTest {

    private final LocalIntentScorer scorer = new LocalIntentScorer();
    private final SlotCompletenessChecker slots = new SlotCompletenessChecker();

    @Test
    @DisplayName("LLM 输出 WRITE + 槽位完整 + 无冲突 → conf 应 > 0.85")
    void highConfidencePath() {
        LlmIntentClassifier.Outcome llm = new LlmIntentClassifier.Outcome(
                IntentLabel.WRITE_PROJECT, 0.95,
                List.of(new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.95)),
                Map.of("target_file", "Foo.java", "change_type", "edit"),
                List.of(),
                ModelRouteHint.CODE,
                false,
                null);
        IntentSignalExtractor.SignalResult sig = new IntentSignalExtractor.SignalResult(
                IntentLabel.WRITE_PROJECT, 0.9, List.of("改"), List.of(), false);

        LocalIntentScorer.Scored r = scorer.score(llm, sig, slots, "改一下 Foo.java");

        assertEquals(IntentLabel.WRITE_PROJECT, r.primary());
        assertTrue(r.confidence() > 0.85, "conf should be > 0.85, got " + r.confidence());
        assertFalse(r.fallback());
    }

    @Test
    @DisplayName("LLM 选 WRITE / 规则强烈建议 READ 且无 negative_signal → 触发 0.15 扣分")
    void ruleConflictPenalty() {
        LlmIntentClassifier.Outcome llm = new LlmIntentClassifier.Outcome(
                IntentLabel.WRITE_PROJECT, 0.9,
                List.of(new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.9)),
                Map.of(),
                List.of(),
                ModelRouteHint.CODE,
                false,
                null);
        IntentSignalExtractor.SignalResult sig = new IntentSignalExtractor.SignalResult(
                IntentLabel.READ_CODE, 0.9, List.of("看"), List.of(), false);

        LocalIntentScorer.Scored r = scorer.score(llm, sig, slots, "看看代码");
        assertTrue(r.confidence() < 0.85, "rule conflict should pull conf below direct threshold, got " + r.confidence());
    }

    @Test
    @DisplayName("negative_signals 非空 → 触发 0.10 扣分")
    void negativeSignalPenalty() {
        LlmIntentClassifier.Outcome llm = new LlmIntentClassifier.Outcome(
                IntentLabel.WRITE_PROJECT, 0.9,
                List.of(new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.9)),
                Map.of(),
                List.of("别"),
                ModelRouteHint.CODE,
                false,
                null);
        IntentSignalExtractor.SignalResult sig = new IntentSignalExtractor.SignalResult(
                IntentLabel.WRITE_PROJECT, 0.9, List.of("改"), List.of("别"), false);

        LocalIntentScorer.Scored r = scorer.score(llm, sig, slots, "改一下");
        assertTrue(r.confidence() < 0.9, "negative should subtract, got " + r.confidence());
    }

    @Test
    @DisplayName("LLM 降级(degraded=true) → 返回 OFF_TOPIC fallback,conf=0.0(方案 B 第二阶段)")
    void degradedLlm() {
        LlmIntentClassifier.Outcome llm = LlmIntentClassifier.Outcome.fallback("parse error");
        IntentSignalExtractor.SignalResult sig = new IntentSignalExtractor.SignalResult(
                IntentLabel.WRITE_PROJECT, 0.9, List.of(), List.of(), false);

        LocalIntentScorer.Scored r = scorer.score(llm, sig, slots, "改一下");
        assertEquals(IntentLabel.OFF_TOPIC, r.primary());
        assertTrue(r.fallback());
        // 方案 B 第二阶段:fallback conf 从 0.5 改成 0.0,
        // 让评测器视为"未决策"不计入 top-1 分母。
        assertEquals(0.0, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("candidates:LLM 给出两个 + 规则第三个 → 合并后按 score 降序")
    void candidateMerging() {
        LlmIntentClassifier.Outcome llm = new LlmIntentClassifier.Outcome(
                IntentLabel.WRITE_PROJECT, 0.8,
                List.of(
                        new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.8),
                        new L1IntentResult.Candidate(IntentLabel.READ_CODE, 0.5)),
                Map.of(),
                List.of(),
                ModelRouteHint.CODE,
                false,
                null);
        IntentSignalExtractor.SignalResult sig = new IntentSignalExtractor.SignalResult(
                IntentLabel.RUN_COMMAND, 0.7, List.of("跑"), List.of(), false);

        LocalIntentScorer.Scored r = scorer.score(llm, sig, slots, "改完跑一下测试");
        assertTrue(r.candidates().size() <= 3);
        assertEquals(IntentLabel.WRITE_PROJECT, r.candidates().get(0).label(),
                "primary should rank first");
    }
}
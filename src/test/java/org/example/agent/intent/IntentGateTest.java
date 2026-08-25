package org.example.agent.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentGateTest {

    private IntentGate gate;
    private StubLlm stubLlm;
    private KeywordSignalExtractor signals;
    private SlotCompletenessChecker slots;
    private LocalIntentScorer scorer;
    private IntentPrompter prompter;
    private IntentFallbackPolicy policy;
    private CliIntentProperties props;

    @BeforeEach
    void setUp() {
        stubLlm = new StubLlm();
        signals = new KeywordSignalExtractor();
        slots = new SlotCompletenessChecker();
        scorer = new LocalIntentScorer();
        prompter = new IntentPrompter(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.PrintWriter(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        prompter.setSuppress(true); // 测试场景不读 stdin
        policy = new IntentFallbackPolicy(new CliIntentProperties());
        props = new CliIntentProperties();
        gate = new IntentGate(stubLlm, signals, slots,
                new SlotCompletenessValidator(),
                new LlmConfidenceCalibrator(new LlmConfidenceCalibrator.Calibration(false, 0, 0, 0, 0, 0, 0)),
                scorer, prompter, policy, props,
                "qwen3.7-flash", "qwen3.7-plus", "qwen3.7-plus", List.of());
    }

    @Test
    @DisplayName("LLM 输出 WRITE + 关键词命中 → conf > 0.85 → 直接 DIRECT,resolvedModel=code model")
    void directPath() {
        stubLlm.next = new LlmIntentClassifier.Outcome(
                IntentLabel.WRITE_PROJECT, 0.95,
                List.of(new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.95)),
                Map.of("target_file", "Foo.java", "change_type", "edit"),
                List.of(),
                ModelRouteHint.CODE, false, null);

        IntentContext ctx = gate.classify("exec-1", "帮我改一下 Foo.java");
        assertEquals(IntentLabel.WRITE_PROJECT, ctx.primaryLabel());
        assertEquals("qwen3.7-plus", ctx.resolvedModel());
        assertEquals(L1IntentResult.Tier.DIRECT, ctx.l1().tier());
    }

    @Test
    @DisplayName("LLM 输出 READ_CODE + model_route_hint=light → resolvedModel=light model")
    void lightRouteResolution() {
        stubLlm.next = new LlmIntentClassifier.Outcome(
                IntentLabel.READ_CODE, 0.95,
                List.of(new L1IntentResult.Candidate(IntentLabel.READ_CODE, 0.95)),
                Map.of("target", "Foo.java"),
                List.of(),
                ModelRouteHint.LIGHT, false, null);

        IntentContext ctx = gate.classify("exec-1", "看看 Foo.java 是怎么写的");
        assertEquals(IntentLabel.READ_CODE, ctx.primaryLabel());
        assertEquals("qwen3.7-flash", ctx.resolvedModel());
    }

    @Test
    @DisplayName("LLM 解析失败 → fallback OFF_TOPIC,conf=0.5,resolvedModel=general")
    void fallbackPath() {
        stubLlm.next = LlmIntentClassifier.Outcome.fallback("timeout");

        IntentContext ctx = gate.classify("exec-1", "改一下");
        assertEquals(IntentLabel.OFF_TOPIC, ctx.primaryLabel());
        assertTrue(ctx.l1().fallback());
        assertEquals("timeout", ctx.l1().fallbackReason());
        assertEquals("qwen3.7-plus", ctx.resolvedModel());
    }

    @Test
    @DisplayName("空输入 → 直接 fallback,不走 LLM")
    void emptyInput() {
        IntentContext ctx = gate.classify("exec-1", "");
        assertEquals(IntentLabel.OFF_TOPIC, ctx.primaryLabel());
        assertEquals(0, stubLlm.calls);
    }

    @Test
    @DisplayName("recentResults 记录每次调用")
    void recentResultsRecorded() {
        stubLlm.next = new LlmIntentClassifier.Outcome(
                IntentLabel.WRITE_PROJECT, 0.95,
                List.of(), Map.of(), List.of(), ModelRouteHint.CODE, false, null);
        gate.classify("exec-a", "改一下");
        stubLlm.next = new LlmIntentClassifier.Outcome(
                IntentLabel.READ_CODE, 0.9,
                List.of(), Map.of(), List.of(), ModelRouteHint.LIGHT, false, null);
        gate.classify("exec-b", "看看");
        assertEquals(2, gate.recentResults().size());
    }

    static class StubLlm implements LlmIntentClassifier {
        int calls = 0;
        Outcome next;
        @Override public Outcome classify(String executionId, String userInput) {
            calls++;
            return next == null ? Outcome.fallback("no stub") : next;
        }
    }
}
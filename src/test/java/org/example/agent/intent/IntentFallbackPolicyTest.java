package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentFallbackPolicyTest {

    @Test
    @DisplayName("默认配置 → 整套启用")
    void defaultsEnabled() {
        IntentFallbackPolicy p = new IntentFallbackPolicy(new CliIntentProperties());
        assertTrue(p.globallyEnabled());
        assertTrue(p.l1Enabled());
        assertTrue(p.l2Enabled());
    }

    @Test
    @DisplayName("cli.intent.enabled=false → 全部禁用")
    void globalSwitchOff() {
        IntentFallbackPolicy p = new IntentFallbackPolicy(
                new CliIntentProperties(false, null, null, null, null));
        assertFalse(p.globallyEnabled());
        assertFalse(p.l1Enabled());
        assertFalse(p.l2Enabled());
    }

    @Test
    @DisplayName("cli.intent.l1.enabled=false → L1 禁用,L2 仍启用")
    void l1OffButL2On() {
        CliIntentProperties props = new CliIntentProperties(true,
                new CliIntentProperties.L1(false, 3000L, null, null, null, null),
                new CliIntentProperties.L2(true, 2000L, null),
                null, null);
        IntentFallbackPolicy p = new IntentFallbackPolicy(props);
        assertTrue(p.globallyEnabled());
        assertFalse(p.l1Enabled());
        assertTrue(p.l2Enabled());
    }

    @Test
    @DisplayName("buildFallback 总是 OFF_TOPIC / conf=0.0 / fallback=true(方案 B 第二阶段)")
    void fallbackShape() {
        IntentFallbackPolicy p = new IntentFallbackPolicy(new CliIntentProperties());
        L1IntentResult r = p.buildFallback("exec-x", "parse error");
        assertEquals(IntentLabel.OFF_TOPIC, r.primary());
        // 方案 B 第二阶段:fallback conf 从 0.5 改成 0.0,
        // 让评测器视为"未决策"不计入 top-1。
        assertEquals(0.0, r.confidence(), 0.001);
        assertTrue(r.fallback());
        assertEquals("parse error", r.fallbackReason());
    }

    @Test
    @DisplayName("fallback.default-label=CHAT_QA → fallback 用 CHAT_QA 兜底")
    void customFallbackLabel() {
        CliIntentProperties props = new CliIntentProperties(true, null, null, null,
                new CliIntentProperties.Fallback(true, IntentLabel.CHAT_QA));
        IntentFallbackPolicy p = new IntentFallbackPolicy(props);
        L1IntentResult r = p.buildFallback("exec-y", "timeout");
        assertEquals(IntentLabel.CHAT_QA, r.primary());
    }
}
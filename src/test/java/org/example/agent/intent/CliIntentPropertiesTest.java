package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliIntentPropertiesTest {

    @Test
    @DisplayName("默认构造 → 默认值就位")
    void defaults() {
        CliIntentProperties p = new CliIntentProperties();
        assertTrue(p.enabled());
        assertEquals(3000L, p.l1().timeoutMs());
        assertEquals(0.85, p.l1().thresholds().direct(), 0.001);
        assertEquals(0.60, p.l1().thresholds().offer(), 0.001);
        assertEquals("qwen3.7-flash", p.modelRouting().light());
        assertEquals("qwen3.7-plus", p.modelRouting().code());
    }

    @Test
    @DisplayName("offer > direct → 自动夹紧")
    void clampThresholds() {
        CliIntentProperties.L1Thresholds t = new CliIntentProperties.L1Thresholds(0.5, 0.9, 0);
        assertEquals(0.5, t.direct(), 0.001);
        assertEquals(0.5, t.offer(), 0.001, "offer 不应超过 direct");
    }

    @Test
    @DisplayName("非正数阈值 → 替换为默认")
    void zeroThresholdsReplaced() {
        CliIntentProperties.L2Thresholds t = new CliIntentProperties.L2Thresholds(0, 0, 0, 0);
        assertEquals(0.80, t.allow(), 0.001);
        assertEquals(0.55, t.warn(), 0.001);
    }

    @Test
    @DisplayName("fallback.default-label=null → 用 CHAT_QA(第三阶段从 OFF_TOPIC 改)")
    void nullFallbackLabel() {
        CliIntentProperties.Fallback f = new CliIntentProperties.Fallback(true, null);
        assertEquals(IntentLabel.CHAT_QA, f.defaultLabel());
    }
}
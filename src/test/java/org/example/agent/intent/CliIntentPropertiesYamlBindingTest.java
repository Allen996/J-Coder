package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Scoring 子记录的默认值与构造契约。
 *
 * <p>yml → record 的实际绑定由 Spring Boot 的 ConfigurationPropertiesBindingPostProcessor
 * 完成(对 {@code w-llm → wLlm} 这种 kebab→camelCase 走 relaxed binding)。
 * 这里只验证 CliIntentProperties 紧凑构造器在 yml 缺省时给出与历史常量一致的值。
 */
class CliIntentPropertiesYamlBindingTest {

    @Test
    @DisplayName("Scoring 缺失时回退到 CliIntentProperties 紧凑构造器的默认值(与历史 LocalIntentScorer 常量一致)")
    void scoringDefaults() {
        CliIntentProperties.Scoring s = new CliIntentProperties().l1().scoring();
        assertEquals(0.6, s.wLlm(), 0.0001);
        assertEquals(0.2, s.wKeyword(), 0.0001);
        assertEquals(0.2, s.wSlot(), 0.0001);
        assertEquals(0.15, s.penConflict(), 0.0001);
        assertEquals(0.10, s.penNegative(), 0.0001);
    }

    @Test
    @DisplayName("非正数/null → 回退到默认值(防 yml 误填)")
    void scoringNegativeGuards() {
        CliIntentProperties.Scoring s = new CliIntentProperties.Scoring(0, 0, 0, 0, 0);
        assertEquals(0.6, s.wLlm(), 0.0001);
        assertEquals(0.2, s.wKeyword(), 0.0001);
        assertEquals(0.2, s.wSlot(), 0.0001);
        assertEquals(0.15, s.penConflict(), 0.0001);
        assertEquals(0.10, s.penNegative(), 0.0001);
    }

    @Test
    @DisplayName("显式正数 → 直接使用(供 eval grid-search 注入)")
    void scoringPassThrough() {
        CliIntentProperties.Scoring s = new CliIntentProperties.Scoring(0.5, 0.3, 0.2, 0.10, 0.05);
        assertEquals(0.5, s.wLlm(), 0.0001);
        assertEquals(0.3, s.wKeyword(), 0.0001);
        assertEquals(0.2, s.wSlot(), 0.0001);
        assertEquals(0.10, s.penConflict(), 0.0001);
        assertEquals(0.05, s.penNegative(), 0.0001);
    }
}
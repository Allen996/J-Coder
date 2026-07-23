package org.example.agent.context.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetPolicyTest {

    @Test
    void defaultPolicyAlignsWithPart3Spec() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        assertThat(p.getContextWindowMax()).isEqualTo(128_000L);
        assertThat(p.getSystemReserved()).isEqualTo(4_000L);
        assertThat(p.getProjectReserved()).isEqualTo(8_000L);
        assertThat(p.getMemoryTokenReservation()).isEqualTo(4_096L);
        assertThat(p.getMaxSingleCallCompletion()).isEqualTo(4_096L);
        assertThat(p.getKeepRecentRounds()).isEqualTo(5);
        assertThat(p.getSummaryTokenCap()).isEqualTo(1_500L);
    }

    @Test
    void sessionReservedIsContextMinusFixed() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        long expected = 128_000L - 4_000L - 8_000L - 4_096L - 4_096L;
        assertThat(p.sessionReserved()).isEqualTo(expected);
    }

    @Test
    void compressionTriggeredAboveEightyPercent() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        long reserved = p.sessionReserved();
        assertThat(p.shouldTriggerCompression(reserved * 79 / 100)).isFalse();
        assertThat(p.shouldTriggerCompression(reserved * 81 / 100)).isTrue();
        assertThat(p.shouldTriggerCompression(reserved)).isTrue();
    }

    @Test
    void textTokenEstimationUsesFourCharsPerToken() {
        assertThat(ContextBudgetPolicy.estimateTextTokens(null)).isEqualTo(0);
        assertThat(ContextBudgetPolicy.estimateTextTokens("")).isEqualTo(0);
        assertThat(ContextBudgetPolicy.estimateTextTokens("abcd")).isEqualTo(1);
        assertThat(ContextBudgetPolicy.estimateTextTokens("abcdefgh")).isEqualTo(2);
        // 1000 字符 ≈ 250 tokens
        assertThat(ContextBudgetPolicy.estimateTextTokens("x".repeat(1000))).isEqualTo(250);
    }
}
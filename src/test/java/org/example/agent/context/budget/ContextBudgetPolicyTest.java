package org.example.agent.context.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetPolicyTest {

    @Test
    void defaultPolicyAlignsWithPart3Spec() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        assertThat(p.getContextWindowMax()).isEqualTo(128_000L);
        assertThat(p.getStaticReserved()).isEqualTo(4_000L);
        assertThat(p.getMemoryTokenReservation()).isEqualTo(4_096L);
        assertThat(p.getMaxSingleCallCompletion()).isEqualTo(4_096L);
        assertThat(p.getKeepRecentRounds()).isEqualTo(5);
        assertThat(p.getSummaryTokenCap()).isEqualTo(1_500L);
    }

    @Test
    void dynamicReservedIsContextMinusFixed() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        long expected = 128_000L - 4_000L - 4_096L - 4_096L;
        assertThat(p.dynamicReserved()).isEqualTo(expected);
    }

    @Test
    void compressionTriggeredAboveEightyPercent() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        long reserved = p.dynamicReserved();
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

    @Test
    void dynamicLayerKeySoftQuotas() {
        ContextBudgetPolicy p = ContextBudgetPolicy.defaultPolicy();
        assertThat(p.getMidTermQuota()).isEqualTo(1_024L);
        assertThat(p.getLongTermQuota()).isEqualTo(2_048L);
        assertThat(p.getMemoryIndexQuota()).isEqualTo(512L);
        assertThat(p.getEphemeralStepBudget()).isEqualTo(2_048L);
        assertThat(p.getMidTermTopN()).isEqualTo(5);
        assertThat(p.getMemoryIndexLru()).isEqualTo(20);
    }
}

package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentAwareToolSetTest {

    private final IntentAwareToolSet set = new IntentAwareToolSet(new org.example.agent.tool.spi.ToolDescriptorRegistry());

    @Test
    @DisplayName("READ_CODE 推荐 read_file/grep/list_dir")
    void readRecommended() {
        assertTrue(set.isRecommendedFor(IntentLabel.READ_CODE, "read_file"));
        assertTrue(set.isRecommendedFor(IntentLabel.READ_CODE, "grep"));
        assertFalse(set.isRecommendedFor(IntentLabel.READ_CODE, "write_file"));
    }

    @Test
    @DisplayName("WRITE_PROJECT 推荐 write_file / edit_file")
    void writeRecommended() {
        assertTrue(set.isRecommendedFor(IntentLabel.WRITE_PROJECT, "write_file"));
        assertTrue(set.isRecommendedFor(IntentLabel.WRITE_PROJECT, "edit_file"));
        assertFalse(set.isRecommendedFor(IntentLabel.WRITE_PROJECT, "run_shell"));
    }

    @Test
    @DisplayName("CHAT_QA 推荐集合为空(继承原 OFF_TOPIC 语义)")
    void chatOffEmpty() {
        assertTrue(set.recommendedFor(IntentLabel.CHAT_QA).isEmpty());
    }
}
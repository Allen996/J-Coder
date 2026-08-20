package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotCompletenessCheckerTest {

    private final SlotCompletenessChecker checker = new SlotCompletenessChecker();

    @Test
    @DisplayName("WRITE_PROJECT 全槽位 → completeness=1.0")
    void writeAllSlots() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("target_file", "Foo.java");
        slots.put("change_type", "rename");
        double c = checker.completeness(IntentLabel.WRITE_PROJECT, slots, null);
        assertEquals(1.0, c, 0.001);
    }

    @Test
    @DisplayName("WRITE_PROJECT 缺 change_type → 0.5")
    void writeMissing() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("target_file", "Foo.java");
        double c = checker.completeness(IntentLabel.WRITE_PROJECT, slots, null);
        assertEquals(0.5, c, 0.001);
    }

    @Test
    @DisplayName("原始输入含 .java → 即使 slots 没填 target_file,启发式补足")
    void writeFileInInputHeuristic() {
        Map<String, Object> slots = new HashMap<>();
        slots.put("change_type", "edit");
        double c = checker.completeness(IntentLabel.WRITE_PROJECT, slots, "改一下 src/main/java/Foo.java 里的方法");
        assertEquals(1.0, c, 0.001);
    }

    @Test
    @DisplayName("CHAT_QA 无必填槽位 → completeness=1.0")
    void chatQaAlwaysFull() {
        double c = checker.completeness(IntentLabel.CHAT_QA, Map.of(), null);
        assertEquals(1.0, c, 0.001);
    }

    @Test
    @DisplayName("READ_CODE 缺 target → 0.0")
    void readMissingTarget() {
        double c = checker.completeness(IntentLabel.READ_CODE, Map.of(), null);
        assertEquals(0.0, c, 0.001);
    }

    @Test
    @DisplayName("requiredSlots 列表")
    void requiredSlots() {
        assertTrue(checker.requiredSlots(IntentLabel.WRITE_PROJECT).contains("target_file"));
        assertEquals(0, checker.requiredSlots(IntentLabel.CHAT_QA).size());
    }
}
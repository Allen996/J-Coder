package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentPrompterTest {

    @Test
    @DisplayName("suppress=true → promptOffer 直接返回 primary")
    void suppressSkips() {
        IntentPrompter p = new IntentPrompter(new ByteArrayInputStream(new byte[0]),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        p.setSuppress(true);
        L1IntentResult r = new L1IntentResult("e", IntentLabel.WRITE_PROJECT, 0.7,
                List.of(new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.7),
                        new L1IntentResult.Candidate(IntentLabel.READ_CODE, 0.5)),
                Map.of(), List.of(), ModelRouteHint.GENERAL, false, null);
        assertEquals(IntentLabel.WRITE_PROJECT, p.promptOffer("改一下", r));
    }

    @Test
    @DisplayName("用户输入 2 → 选第二个候选")
    void pickByIndex() {
        IntentPrompter p = new IntentPrompter(
                new ByteArrayInputStream("2\n".getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        L1IntentResult r = new L1IntentResult("e", IntentLabel.WRITE_PROJECT, 0.7,
                List.of(new L1IntentResult.Candidate(IntentLabel.WRITE_PROJECT, 0.7),
                        new L1IntentResult.Candidate(IntentLabel.READ_CODE, 0.5)),
                Map.of(), List.of(), ModelRouteHint.GENERAL, false, null);
        assertEquals(IntentLabel.READ_CODE, p.promptOffer("改一下", r));
    }

    @Test
    @DisplayName("用户输入 0 → 退回 primary")
    void pickZero() {
        IntentPrompter p = new IntentPrompter(
                new ByteArrayInputStream("0\n".getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        L1IntentResult r = new L1IntentResult("e", IntentLabel.WRITE_PROJECT, 0.7,
                List.of(new L1IntentResult.Candidate(IntentLabel.READ_CODE, 0.6)),
                Map.of(), List.of(), ModelRouteHint.GENERAL, false, null);
        assertEquals(IntentLabel.WRITE_PROJECT, p.promptOffer("改一下", r));
    }

    @Test
    @DisplayName("用户空回车 → 退回 primary")
    void pickEmpty() {
        IntentPrompter p = new IntentPrompter(
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        L1IntentResult r = new L1IntentResult("e", IntentLabel.WRITE_PROJECT, 0.7,
                List.of(new L1IntentResult.Candidate(IntentLabel.READ_CODE, 0.6)),
                Map.of(), List.of(), ModelRouteHint.GENERAL, false, null);
        assertEquals(IntentLabel.WRITE_PROJECT, p.promptOffer("改一下", r));
    }

    @Test
    @DisplayName("promptClarify 用户给新文本 → 返回新文本")
    void clarifyReturnsUserText() {
        IntentPrompter p = new IntentPrompter(
                new ByteArrayInputStream("跑测试\n".getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        L1IntentResult r = new L1IntentResult("e", IntentLabel.CHAT_QA, 0.3,
                List.of(new L1IntentResult.Candidate(IntentLabel.CHAT_QA, 0.3)),
                Map.of(), List.of(), ModelRouteHint.GENERAL, false, null);
        assertEquals("跑测试", p.promptClarify("?", r));
    }

    @Test
    @DisplayName("promptClarify 用户空 → 退回原 input")
    void clarifyEmptyStays() {
        IntentPrompter p = new IntentPrompter(
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        L1IntentResult r = new L1IntentResult("e", IntentLabel.CHAT_QA, 0.3,
                List.of(), Map.of(), List.of(), ModelRouteHint.GENERAL, false, null);
        assertEquals("?", p.promptClarify("?", r));
    }
}
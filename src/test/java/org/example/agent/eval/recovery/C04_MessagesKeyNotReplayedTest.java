package org.example.agent.eval.recovery;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度 C KPI：ContextBuilder 装配时，MESSAGES key 不会无脑重放全量历史。
 * 对应 TEST.md §5.1 "C 维度 / C04 messages key 节流比"。
 *
 * <p>100 轮长对话（每条约 1k token）→ 走 ContextBuilder.build() → MESSAGES key 经 ConversationCompressor
 * 压缩后，token 数必须 ≤ dynamicReserved，且相对全量历史的节流比 ≥ 5×。
 *
 * <p>这保证了：恢复 plan 后进入下一轮 ReAct 时，LLM 看到的 messages 段是受控的，不会被历史洪水淹没。
 */
class C04_MessagesKeyNotReplayedTest {

    @Test
    void messagesKeyStaysWithinBudgetAfterHeavyHistory() {
        ContextBudgetPolicy policy = ContextBudgetPolicy.defaultPolicy();
        long dynamicReserved = policy.dynamicReserved();

        // ---- 1) 构造 100 轮长对话 ----
        int rounds = 100;
        String padding = repeat('x', 4_000);  // ≈ 1k token / 条

        SessionMessageStore store = new SessionMessageStore();
        String sessionId = "recovery-session-001";
        for (int i = 0; i < rounds; i++) {
            store.addUser(sessionId, "u" + i + " " + padding);
            store.addAssistant(sessionId, "a" + i + " " + padding);
        }

        // ---- 2) 装配上下文 ----
        AgentTask task = AgentTask.builder()
                .sessionId(sessionId)
                .input("next turn")
                .role("chat")
                .promptId("chat.react-assistant")
                .build();

        ContextBuilder builder = ContextBuilder.minimal(
                policy,
                store,
                new ConversationCompressor());

        ContextBuilder.BuiltContext built = builder.build(task, "next user turn");

        // ---- 3) 取出 MESSAGES key ----
        ContextEntry messages = built.getDynamicLayer()
                .get(ContextKey.MESSAGES)
                .orElseThrow(() -> new AssertionError("MESSAGES key must be present after build()"));

        // ---- 4) 计算全量历史的 token 数（基线）----
        long fullHistoryTokens = 0L;
        List<Message> all = store.get(sessionId).snapshot();
        for (Message m : all) {
            fullHistoryTokens += ContextBudgetPolicy.estimateTextTokens(
                    SessionMessageStore.extractText(m));
        }

        long messagesTokens = messages.getEstimatedTokens();

        // ---- KPI 1：MESSAGES key 的 token ≤ dynamicReserved ----
        assertThat(messagesTokens)
                .as("MESSAGES key tokens must fit dynamic budget (tokens=%d, reserved=%d)",
                        messagesTokens, dynamicReserved)
                .isLessThanOrEqualTo(dynamicReserved);

        // ---- KPI 2：节流比 ≥ 5× ----
        double throttle = (double) fullHistoryTokens / Math.max(1L, messagesTokens);
        assertThat(throttle)
                .as("MESSAGES key must be at least 5× smaller than full history (full=%d, key=%d, ratio=%.2f)",
                        fullHistoryTokens, messagesTokens, throttle)
                .isGreaterThanOrEqualTo(5.0);
    }

    @Test
    void shortHistoryKeepsFullRoundsWithoutCompression() {
        // 反例断言：短历史不应被压缩
        ContextBudgetPolicy policy = ContextBudgetPolicy.defaultPolicy();

        SessionMessageStore store = new SessionMessageStore();
        String sessionId = "short";
        for (int i = 0; i < 3; i++) {
            store.addUser(sessionId, "u" + i);
            store.addAssistant(sessionId, "a" + i);
        }

        AgentTask task = AgentTask.builder()
                .sessionId(sessionId)
                .input("next").role("chat").promptId("chat.react-assistant").build();

        ContextBuilder builder = ContextBuilder.minimal(policy, store,
                new ConversationCompressor());
        ContextBuilder.BuiltContext built = builder.build(task, "next turn");

        ContextEntry messages = built.getDynamicLayer()
                .get(ContextKey.MESSAGES)
                .orElseThrow();

        // 6 条原文（3 轮）必须完整保留
        String text = messages.getText();
        assertThat(text)
                .contains("u0").contains("a0")
                .contains("u1").contains("a1")
                .contains("u2").contains("a2");
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}
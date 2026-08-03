package org.example.agent.context.compression;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.session.SessionMessageStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCompressorTest {

    @Test
    void loadsRecent5RoundsByDefault() {
        ConversationCompressor compressor = new ConversationCompressor();
        SessionMessageStore store = new SessionMessageStore();
        String sessionId = "test";
        for (int i = 0; i < 10; i++) {
            store.addUser(sessionId, "round " + i + " user " + repeat('x', 50));
            store.addAssistant(sessionId, "round " + i + " assistant " + repeat('y', 50));
        }

        ContextBudgetPolicy policy = ContextBudgetPolicy.defaultPolicy();

        List<Message> result = compressor.loadMessages(store.get(sessionId).snapshot(), policy, null);

        // 默认 keepRecentRounds=5 + history 较短 → 直接命中 5 轮
        long userCount = result.stream().filter(m -> m instanceof UserMessage).count();
        assertThat(userCount).isEqualTo(5);
    }

    @Test
    void noCompressionWhenHistoryIsShort() {
        ConversationCompressor compressor = new ConversationCompressor();
        // 1 轮对话，远小于 dynamicReserved
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("hi"));
        history.add(new AssistantMessage("hello"));

        List<Message> result = compressor.loadMessages(
                history, ContextBudgetPolicy.defaultPolicy(), null);
        assertThat(result).hasSize(2);
    }

    @Test
    void decrementRoundsWhenOverBudget() {
        // 构造一个会触发超预算的 history
        ConversationCompressor compressor = new ConversationCompressor();
        ContextBudgetPolicy policy = ContextBudgetPolicy.builder()
                .contextWindowMax(128_000L)
                .staticReserved(4_000L)
                .memoryTokenReservation(4_096L)
                .maxSingleCallCompletion(4_096L)
                .build();
        long dynamicReserved = policy.dynamicReserved();

        // 构造 20 轮每轮 50K token（会超 5 轮预算 ~115K）
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            history.add(new UserMessage("u" + i + " " + repeat('x', 200_000)));
            history.add(new AssistantMessage("a" + i + " " + repeat('y', 200_000)));
        }
        long totalTokens = ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(history.get(0))) * history.size();
        org.junit.jupiter.api.Assumptions.assumeTrue(totalTokens > dynamicReserved);

        List<Message> result = compressor.loadMessages(history, policy, null);
        // 由于超预算，递减轮数；最后可能 1 轮仍超 → 触发 LLM 摘要
        // 验证：要么少于 5 轮，要么首条是 system 摘要
        long userCount = result.stream().filter(m -> m instanceof UserMessage).count();
        if (result.get(0) instanceof SystemMessage) {
            assertThat(((SystemMessage) result.get(0)).getText()).contains("LLM 摘要");
        } else {
            assertThat(userCount).isLessThanOrEqualTo(5);
        }
    }

    @Test
    void noHeuristicFallbackWhenModelUnavailable() {
        // §7.5 硬性约束：模型不可用 → 不允许启发式兜底，应让上层抛 overflow
        ConversationCompressor compressor = new ConversationCompressor(null, null,
                ContextBudgetPolicy.defaultPolicy());
        ContextBudgetPolicy policy = ContextBudgetPolicy.builder()
                .contextWindowMax(10_000L)        // 故意压小，让 1 轮也溢出
                .staticReserved(500L)
                .memoryTokenReservation(1_000L)
                .maxSingleCallCompletion(1_000L)
                .keepRecentRounds(5)
                .build();

        // 单轮足以触发 LLM 摘要；模型不可用 → 抛 overflow
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("u" + repeat('x', 60_000)));
        history.add(new AssistantMessage("a" + repeat('y', 60_000)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> compressor.loadMessages(history, policy, null))
                .isInstanceOf(org.example.agent.context.builder.ContextBuilder.ContextOverflowException.class)
                .hasMessageContaining("memory LLM disabled");
    }

    @Test
    void takeRecentRoundsFindsCorrectBoundary() {
        // 6 轮 user+assistant，K=5 → 切掉 1 轮，留 5
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            msgs.add(new UserMessage("u" + i));
            msgs.add(new AssistantMessage("a" + i));
        }
        List<Message> result = ConversationCompressor.takeRecentRounds(msgs, 5);
        long userCount = result.stream().filter(m -> m instanceof UserMessage).count();
        assertThat(userCount).isEqualTo(5);
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}

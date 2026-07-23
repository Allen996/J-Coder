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
    void keepsRecentRoundsAndProducesSummaryMessage() {
        // Arrange
        ConversationCompressor compressor = new ConversationCompressor();
        SessionMessageStore store = new SessionMessageStore();
        String sessionId = "test";
        // 10 轮 user+assistant：最近 5 轮保留，其余摘要
        for (int i = 0; i < 10; i++) {
            store.addUser(sessionId, "round " + i + " user message " + repeat('x', 100));
            store.addAssistant(sessionId, "round " + i + " assistant " + repeat('y', 100));
        }

        ContextBudgetPolicy policy = ContextBudgetPolicy.defaultPolicy();

        // Act
        List<Message> compressed = compressor.compress(store.get(sessionId).snapshot(), policy, null);

        // Assert
        assertThat(compressed).isNotEmpty();
        // 第一条是 system 摘要
        assertThat(compressed.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(compressed.get(0).getText()).contains("早期对话摘要");
        // 总长度应小于压缩前
        assertThat(compressed.size()).isLessThan(store.get(sessionId).size());
        // 不应该出现超过 5 轮的 user（保留最近 5 轮）
        long userCount = compressed.stream().filter(m -> m instanceof UserMessage).count();
        assertThat(userCount).isLessThanOrEqualTo(5);
    }

    @Test
    void noCompressionWhenHistoryIsShort() {
        ConversationCompressor compressor = new ConversationCompressor();
        SessionMessageStore store = new SessionMessageStore();
        String sessionId = "short";
        store.addUser(sessionId, "hi");
        store.addAssistant(sessionId, "hello");

        List<Message> result = compressor.compress(
                store.get(sessionId).snapshot(),
                ContextBudgetPolicy.defaultPolicy(),
                null);
        assertThat(result).hasSize(2);
    }

    @Test
    void splitKeepRecentFindsCorrectBoundary() {
        // 6 轮 user+assistant，K=5 → 切掉 1 轮，留 5
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            msgs.add(new UserMessage("u" + i));
            msgs.add(new AssistantMessage("a" + i));
        }
        int idx = ConversationCompressor.splitKeepRecent(msgs, 5);
        // 最近 5 个 user 中最早的那个的 index = 12（user5 在 index 10，user4 在 index 8，user3 在 6，user2 在 4，user1 在 2 → 第 5 个 = index 2）
        // 实际：逆序数 6 个 user，K=5，命中第 5 个时 targetUserStartIdx 就是 user1 的位置
        assertThat(idx).isEqualTo(2);
    }

    @Test
    void heuristicFallbackProducesReadableOutput() {
        List<Message> msgs = List.of(
                new UserMessage("读一下文件 X"),
                new AssistantMessage("好的，我读了 X")
        );
        String s = ConversationCompressor.localHeuristicSummary(msgs);
        assertThat(s).contains("本地摘要");
        assertThat(s).contains("[用户]");
        assertThat(s).contains("[助手]");
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}
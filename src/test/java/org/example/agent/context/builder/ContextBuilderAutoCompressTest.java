package org.example.agent.context.builder;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.AgentTask;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ContextBuilder.build 的同步自动压缩触发。
 *
 * <p>用例：(static + dynamic + input) > 0.8 * contextWindowMax 时,
 * {@code Session#compressionCount()} 应该 +1。
 */
class ContextBuilderAutoCompressTest {

    @Test
    void autoCompressTriggersWhenTotalAbove80PctOfWindow() {
        // windowMax = 10_000,80% = 8_000。构造 static ≈ 125, dynamic ≈ 4_500, input ≈ 3_500。
        // 总和 ≈ 8_125 > 8000,触发;压缩后 messagesBudget = 8_000 − 125 − 3_500 = 4_375 > 0。
        ContextBudgetPolicy policy = ContextBudgetPolicy.builder()
                .contextWindowMax(10_000L)
                .staticReserved(500L)
                .memoryTokenReservation(500L)
                .maxSingleCallCompletion(500L)
                .keepRecentRounds(2)
                .summaryTokenCap(300L)
                .build();

        SessionMessageStore store = new SessionMessageStore();
        String sid = "s-auto";
        // session 历史做大一点(每轮 ≈ 200 token,k=2 → ~400 tokens,故意再多以突破)
        for (int i = 0; i < 12; i++) {
            store.addUser(sid, "u" + i + " " + repeat('a', 4000));   // ≈ 1k token
            store.addAssistant(sid, "a" + i + " " + repeat('b', 4000));
        }

        ConversationCompressor compressor = new ConversationCompressor();
        ContextBuilder builder = ContextBuilder.minimal(policy, store, compressor);

        // input ≈ 5_000 token ≈ 20_000 chars。配 session ≈ 6_000 token、static ≈ 125,
        // total ≈ 11_125 > 8_000 → 触发;messagesBudget = 8_000 − 125 − 5_000 = 2_875。
        String hugeInput = repeat('X', 20_000);
        AgentTask task = AgentTask.builder()
                .sessionId(sid)
                .input("")
                .role("chat")
                .build();

        // 第一次 build:输入巨大 -> 触发自动压缩 -> compressionCount += 1
        try {
            builder.build(task, hugeInput);
        } catch (RuntimeException ex) {
            System.out.println("DEBUG build threw: " + ex.getMessage());
            throw ex;
        }
        SessionMessageStore.Session s = store.getOrCreate(sid);
        assertThat(s.compressionCount())
                .as("auto-compression should fire when total > 0.8 * windowMax")
                .isGreaterThanOrEqualTo(1);
        assertThat(s.lastCompressionBeforeUsed()).isGreaterThan(0L);
        assertThat(s.lastCompressionAfterUsed()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void autoCompressDoesNotTriggerWhenBelowThreshold() {
        ContextBudgetPolicy policy = ContextBudgetPolicy.builder()
                .contextWindowMax(128_000L)
                .staticReserved(4_000L)
                .memoryTokenReservation(4_096L)
                .maxSingleCallCompletion(4_096L)
                .build();
        SessionMessageStore store = new SessionMessageStore();
        String sid = "s-quiet";
        store.addUser(sid, "short user msg");
        store.addAssistant(sid, "short assistant msg");

        ConversationCompressor compressor = new ConversationCompressor();
        ContextBuilder builder = ContextBuilder.minimal(policy, store, compressor);
        AgentTask task = AgentTask.builder()
                .sessionId(sid).input("").role("chat").build();

        builder.build(task, "tiny input");
        assertThat(store.getOrCreate(sid).compressionCount()).isEqualTo(0);
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}

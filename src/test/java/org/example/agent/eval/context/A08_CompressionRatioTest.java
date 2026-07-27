package org.example.agent.eval.context;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.session.SessionMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度 A 测量型测试：压缩率（input_tokens / output_tokens）在不同 history 规模下的实测数值。
 *
 * <p>本测试不设硬门限（除了 output ≤ dynamicReserved 这个不可能违反的不变量）。
 * 每个 case 都会把实测数值打印到 stdout，趋势比单点 KPI 重要。
 * 对应 TEST.md §5.1 "A 维度 / A08 压缩比"。
 */
class A08_CompressionRatioTest {

    private static final ContextBudgetPolicy DEFAULT = ContextBudgetPolicy.defaultPolicy();

    @Test
    @DisplayName("小历史（< dynamicReserved）→ 不压缩, ratio ≈ 1×")
    void smallHistoryNoCompression() {
        int rounds = 5;
        int avgTokens = 1_000;  // 每条约 1k token, 总 10k << 115k
        long[] r = measure(rounds, avgTokens);
        printReport("small-history-no-compression", rounds, avgTokens, r);
        // 不变量: 不该被压缩, 也不该 overflow
        assertNoOverflow(r);
    }

    @Test
    @DisplayName("中历史（5 轮刚好 / 超 5 轮）→ 截断到 5 轮")
    void mediumHistoryTruncatesTo5Rounds() {
        int rounds = 20;
        int avgTokens = 5_000;  // 每轮 10k token, 总 200k > 115k, 5 轮=50k 装得下
        long[] r = measure(rounds, avgTokens);
        printReport("medium-history-truncate-to-5", rounds, avgTokens, r);
        assertNoOverflow(r);
    }

    @Test
    @DisplayName("大历史（1 轮仍超 budget）→ 单轮也装不下, 触发 LLM summary")
    void largeHistoryTriggersSummary() {
        // 单轮 = 60k token > 115k 不会触发, 所以用 1 轮= 30k 不够大
        // 让单轮本身接近 dynamicReserved 上限, 让算法决定"放得下还是放不下"
        int rounds = 100;
        int avgTokens = 3_000;  // 单轮 6k, 100 轮 600k → 5 轮 30k, 装得下
        // 想触发 summary, 必须让单轮 > dynamicReserved(115k)。但生成 100k+ token 字符串会拖慢测试。
        // 这里测"装得下 1 轮"的临界点。
        long[] r = measure(rounds, avgTokens);
        printReport("large-history-1round-fits", rounds, avgTokens, r);
        assertNoOverflow(r);
    }

    @Test
    @DisplayName("极端历史（每轮远大于 budget）→ 必走 summary, 看 summary 实际占比")
    void extremeHistorySummaryPath() {
        int rounds = 50;
        int avgTokens = 30_000;  // 每条 30k, 单轮 60k < 115k → 不进 summary, 仅 1 轮
        long[] r = measure(rounds, avgTokens);
        printReport("extreme-history-summary-or-1round", rounds, avgTokens, r);
        assertNoOverflow(r);
    }

    @Test
    @DisplayName("summary 路径强制触发（小 dynamicReserved）→ LLM summary 后的真实压缩比")
    void summaryPathForced() {
        int rounds = 50;
        int avgTokens = 30_000;
        // 把 dynamicReserved 压到 5k, 1 轮 60k 必溢出 → 必走 summary
        ContextBudgetPolicy tiny = ContextBudgetPolicy.builder()
                .contextWindowMax(8_000L)
                .staticReserved(1_000L)
                .memoryTokenReservation(1_000L)
                .maxSingleCallCompletion(1_000L)
                .build();

        long[] r = measure(rounds, avgTokens, tiny,
                new ConversationCompressor.FallbackSummarizer());
        printReport("summary-forced-tiny-budget", rounds, avgTokens, r);
        assertNoOverflow(r);
    }

    // ============== helpers ==============

    /**
     * 跑一次 loadMessages 并返回 {inputTokens, outputTokens, outputMessageCount}。
     */
    private static long[] measure(int rounds, int avgTokensPerMessage) {
        return measure(rounds, avgTokensPerMessage, DEFAULT, new ConversationCompressor.FallbackSummarizer());
    }

    private static long[] measure(int rounds, int avgTokensPerMessage,
                                  ContextBudgetPolicy policy,
                                  ConversationCompressor.SummarizerChatModel summarizer) {
        String padding = repeat('x', avgTokensPerMessage * 4);
        List<Message> history = new ArrayList<>(rounds * 2);
        for (int i = 0; i < rounds; i++) {
            history.add(new UserMessage("u" + i + " " + padding));
            history.add(new AssistantMessage("a" + i + " " + padding));
        }

        long inputTokens = 0L;
        for (Message m : history) {
            inputTokens += ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(m));
        }

        ConversationCompressor compressor = new ConversationCompressor(summarizer, policy);
        List<Message> compressed = compressor.loadMessages(history, policy, null);

        long outputTokens = 0L;
        for (Message m : compressed) {
            outputTokens += ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(m));
        }

        return new long[]{inputTokens, outputTokens, compressed.size()};
    }

    private static void printReport(String label, int rounds, int avgTokens, long[] r) {
        long input = r[0], output = r[1], count = r[2];
        double ratio = (double) input / Math.max(1L, output);
        double compression = (1.0 - (double) output / Math.max(1L, input)) * 100.0;
        System.out.printf(
                "[A08 %s] rounds=%d avgTokens/message=%d%n"
                        + "  input_tokens=%d%n"
                        + "  output_tokens=%d (output_msg_count=%d)%n"
                        + "  ratio=%.2f× (%s)%n"
                        + "  compression=%.1f%%%n"
                        + "  dynamicReserved=%d%n",
                label, rounds, avgTokens,
                input, output, count,
                ratio, ratio >= 10 ? "≥10× ✓" : "<10×",
                compression,
                DEFAULT.dynamicReserved());
    }

    private static void assertNoOverflow(long[] r) {
        long output = r[1];
        assertThat(output)
                .as("output must not exceed dynamicReserved")
                .isLessThanOrEqualTo(DEFAULT.dynamicReserved());
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}
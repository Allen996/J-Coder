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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度 A 测量型测试：不同"摘要器质量"下的事实召回率（recalled / total）。
 *
 * <p>本测试聚焦在"召回率实测数值", 不用单一 stub summarizer 蒙混过关。
 * 提供 4 个对照场景（完美 / 半残 / 仅首条 / 启发式 fallback）, 每个 case 打印实测 recall。
 * 真实场景请替换 {@link #REAL_DASHSCOPE_SUMMARIZER} 接入 DashScope, 看真实召回率。
 *
 * <p>对应 TEST.md §5.1 "A 维度 / A09 事实召回率"。
 */
class A09_FactRecallAfterCompressionTest {

    private static final ContextBudgetPolicy TINY = ContextBudgetPolicy.builder()
            .contextWindowMax(8_000L)
            .staticReserved(1_000L)
            .memoryTokenReservation(1_000L)
            .maxSingleCallCompletion(1_000L)
            .build();

    /**
     * 接入真实 LLM 摘要器的占位（默认 null = 不测真实场景）。
     * 想跑真实数据：注入 DashScopeChatModel 实现的 SummarizerChatModel，赋值到这里。
     */
    private static final ConversationCompressor.SummarizerChatModel REAL_DASHSCOPE_SUMMARIZER = null;

    @Test
    @DisplayName("完美摘要器（保留全部事实）→ recall = 1.00, 理论上限")
    void perfectSummarizer() {
        Set<String> facts = injectFacts(50);
        double recall = measureRecall(facts,
                new PerfectSummarizer(facts),
                "perfect-summarizer");
        // 不设硬门限, 只打印
    }

    @Test
    @DisplayName("半残摘要器（仅保留前半事实）→ recall ≈ 0.50")
    void halfSummarizer() {
        Set<String> facts = injectFacts(50);
        double recall = measureRecall(facts,
                new PartialSummarizer(facts, 0.5),
                "half-summarizer");
    }

    @Test
    @DisplayName("最差摘要器（仅保留首条）→ recall ≈ 0.02")
    void firstOnlySummarizer() {
        Set<String> facts = injectFacts(50);
        double recall = measureRecall(facts,
                new FirstOnlySummarizer(facts),
                "first-only-summarizer");
    }

    @Test
    @DisplayName("启发式 fallback（无 LLM, 首行拼接）→ 实际 recall")
    void heuristicFallback() {
        Set<String> facts = injectFacts(50);
        double recall = measureRecall(facts,
                new ConversationCompressor.FallbackSummarizer(),
                "heuristic-fallback");
    }

    @Test
    @DisplayName("真实 DashScope 摘要器（若已注入）→ 真实生产 recall")
    void realDashScopeSummarizer() {
        if (REAL_DASHSCOPE_SUMMARIZER == null) {
            System.out.println("[A09 real-dashscope] SKIPPED — REAL_DASHSCOPE_SUMMARIZER 未注入");
            return;
        }
        Set<String> facts = injectFacts(50);
        measureRecall(facts, REAL_DASHSCOPE_SUMMARIZER, "real-dashscope");
    }

    // ============== helpers ==============

    /**
     * 注入 N 条 "FACT_i=VALUE_i" 事实到 history, 跑 loadMessages, 计算 recall。
     * 强制走 summary 路径（用 TINY policy, dynamicReserved=5k, 单轮 60k 必溢出）。
     */
    private static double measureRecall(Set<String> facts,
                                        ConversationCompressor.SummarizerChatModel summarizer,
                                        String label) {
        int rounds = facts.size();
        List<Message> history = new ArrayList<>(rounds * 2);
        for (int i = 0; i < rounds; i++) {
            String fact = "FACT_" + i + "=VALUE_" + i;
            String padding = repeat('x', 30_000 * 4);
            history.add(new UserMessage("u" + i + " " + fact + " " + padding));
            history.add(new AssistantMessage("a" + i + " ack " + fact));
        }

        ConversationCompressor compressor = new ConversationCompressor(summarizer, TINY);
        List<Message> result = compressor.loadMessages(history, TINY, null);

        StringBuilder allText = new StringBuilder();
        for (Message m : result) {
            allText.append(SessionMessageStore.extractText(m)).append('\n');
        }

        Set<String> recalled = new HashSet<>();
        for (String f : facts) {
            if (allText.toString().contains(f)) recalled.add(f);
        }
        double recall = (double) recalled.size() / facts.size();

        long inputTokens = 0L, outputTokens = 0L;
        for (Message m : history) inputTokens += ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(m));
        for (Message m : result)   outputTokens += ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(m));

        System.out.printf(
                "[A09 %s]%n"
                        + "  facts_total=%d%n"
                        + "  facts_recalled=%d%n"
                        + "  recall=%.3f%n"
                        + "  input_tokens=%d, output_tokens=%d, ratio=%.2f×%n"
                        + "  output_msg_count=%d%n",
                label, facts.size(), recalled.size(), recall,
                inputTokens, outputTokens, (double) inputTokens / Math.max(1L, outputTokens),
                result.size());

        return recall;
    }

    private static Set<String> injectFacts(int n) {
        Set<String> facts = new HashSet<>();
        for (int i = 0; i < n; i++) facts.add("FACT_" + i + "=VALUE_" + i);
        return facts;
    }

    // ============== 摘要器 stub ==============

    /** 完美摘要器: 把所有事实一字不漏保留。理论上限。 */
    static class PerfectSummarizer implements ConversationCompressor.SummarizerChatModel {
        private final Set<String> facts;
        PerfectSummarizer(Set<String> facts) { this.facts = facts; }
        @Override public String summarize(String sys, List<Message> history) {
            StringBuilder sb = new StringBuilder("(perfect)\n");
            for (String f : facts) sb.append(f).append('\n');
            return sb.toString();
        }
    }

    /** 半残摘要器: 只保留前半的事实。模拟"LLM 漏掉一半"。 */
    static class PartialSummarizer implements ConversationCompressor.SummarizerChatModel {
        private final Set<String> facts;
        private final double keepRatio;
        PartialSummarizer(Set<String> facts, double keepRatio) {
            this.facts = facts; this.keepRatio = keepRatio;
        }
        @Override public String summarize(String sys, List<Message> history) {
            StringBuilder sb = new StringBuilder("(partial)\n");
            int keep = (int) (facts.size() * keepRatio);
            int i = 0;
            for (String f : facts) {
                if (i++ < keep) sb.append(f).append('\n');
            }
            return sb.toString();
        }
    }

    /** 仅首条: 模拟"摘要器完全丢失历史, 只保留最近一行"。 */
    static class FirstOnlySummarizer implements ConversationCompressor.SummarizerChatModel {
        private final Set<String> facts;
        FirstOnlySummarizer(Set<String> facts) { this.facts = facts; }
        @Override public String summarize(String sys, List<Message> history) {
            return "(first-only)\n" + facts.iterator().next() + "\n";
        }
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }
}
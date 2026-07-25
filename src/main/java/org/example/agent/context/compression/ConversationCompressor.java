package org.example.agent.context.compression;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.observer.ContextCompressionHook;
import org.example.agent.core.task.AgentTask;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话压缩器（part3.md §6.6 压缩策略 §1）。
 *
 * <p>新算法（替换原"最近 K 轮 + 摘要"滑窗）：
 * <ol>
 *   <li>取最近 5 轮 = 5 user + 5 assistant 完整记录（含 tool_call / tool_response）。</li>
 *   <li>若总 token 仍超 dynamicReserved，则递减轮数 4 → 3 → 2 → 1。</li>
 *   <li>若 1 轮仍超，对该单轮调用 LLM 摘要压缩。</li>
 *   <li>① 仍然失败 → 抛 ContextOverflowException，由 TokenBudgetObserver 终止（CONTEXT_OVERFLOW）。</li>
 * </ol>
 *
 * <p>不再使用"滑窗淘汰 + 占位符"。压缩仅针对 messages key；
 * memory_index / mid_term / long_term / ephemeral 由各自独立的策略控制。
 *
 * <p>本类同时实现 {@link ContextCompressionHook} 接口，注入到 {@link org.example.agent.core.observer.TokenBudgetObserver} 后即生效。
 * v1 简化：Hook 入口返回 false，让 ContextBuilder 在 build() 时主动调用 {@link #loadMessages}。
 */
@Slf4j
@Component
public class ConversationCompressor implements ContextCompressionHook {

    /** 单轮 LLM 摘要提示词。 */
    public static final String SINGLE_ROUND_SUMMARY_PROMPT = """
            你是会话摘要器。给定单个对话轮（user + assistant + 工具调用结果），请用结构化中文输出 200 token 以内的摘要。

            摘要必须包含：
            1. 用户的提问
            2. 助手做了什么（描述工具调用与关键结论）
            3. 是否完成 / 给出了什么成果

            风格：客观、第三人称，不添加原对话没有的信息；不要编造文件路径。
            """;

    private final SummarizerChatModel summarizer;
    private final ContextBudgetPolicy policy;

    public ConversationCompressor() {
        this(new FallbackSummarizer(), ContextBudgetPolicy.defaultPolicy());
    }

    @Autowired
    public ConversationCompressor(SummarizerChatModel summarizer, ContextBudgetPolicy policy) {
        this.summarizer = summarizer == null ? new FallbackSummarizer() : summarizer;
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
    }

    @Override
    public boolean tryCompress(String executionId, long promptTokens, long effectiveBudget) {
        log.debug("CompressionHook invoked but routed through ContextBuilder: executionId={} prompt={}/{}",
                executionId, promptTokens, effectiveBudget);
        return false;
    }

    /**
     * 按 part3.md §6.6 规则 1 加载 messages：
     * <ol>
     *   <li>默认取最近 5 轮。</li>
     *   <li>超预算则 4 → 3 → 2 → 1 递减。</li>
     *   <li>1 轮仍超则对单轮做 LLM 摘要压缩。</li>
     *   <li>仍超 → 抛 {@link ContextOverflowException}。</li>
     * </ol>
     *
     * @param history 当前 session 累积的全部消息
     * @param policy  预算策略
     * @param task    当前 AgentTask（用于日志关联，可选）
     * @return 压缩后的消息列表（可能是单轮或多轮原文 + 摘要）
     */
    public List<Message> loadMessages(List<Message> history, ContextBudgetPolicy policy, AgentTask task) {
        if (history == null || history.isEmpty()) return new ArrayList<>();
        ContextBudgetPolicy p = policy == null ? this.policy : policy;
        long dynamicReserved = p.dynamicReserved();
        int startRounds = p.getKeepRecentRounds();

        // 1) 从 keepRecentRounds 递减到 1
        for (int rounds = startRounds; rounds >= 1; rounds--) {
            List<Message> slice = takeRecentRounds(history, rounds);
            long used = estimateMessagesTokens(slice);
            if (used <= dynamicReserved) {
                return slice;
            }
            log.debug("loadMessages: {} rounds used {} > reserved {}, trying fewer rounds",
                    rounds, used, dynamicReserved);
        }

        // 2) 1 轮仍超：对单轮做 LLM 摘要压缩
        List<Message> singleRound = takeRecentRounds(history, 1);
        if (singleRound.isEmpty()) {
            throw new ContextBuilder.ContextOverflowException(
                    "history cannot produce any round",
                    estimateMessagesTokens(history), dynamicReserved);
        }
        String summary = trySummarizeRound(singleRound);
        if (summary == null) summary = localHeuristicSummary(singleRound);
        summary = truncateToTokens(summary, p.getSummaryTokenCap());
        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage("（以下是 1 轮对话的 LLM 摘要，原始消息因长度超出已折叠）\n" + summary));
        long used = estimateMessagesTokens(compressed);
        log.info("loadMessages: 1-round overflow, summarized to {} tokens", used);

        // 3) 仍超 → 抛 overflow
        if (used > dynamicReserved) {
            throw new ContextBuilder.ContextOverflowException(
                    "single round summary exceeds reserved budget: " + used + " > " + dynamicReserved,
                    used, dynamicReserved);
        }
        return compressed;
    }

    /**
     * 取最近 K 轮对话。
     * <p>每轮定义：从一个 user 起，到下一个 user 之前结束（即 user + 后续 assistant / tool_call / tool_response）。
     */
    static List<Message> takeRecentRounds(List<Message> history, int k) {
        if (k <= 0 || history.isEmpty()) return new ArrayList<>();
        int targetUserStart = -1;
        int userCount = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                userCount++;
                if (userCount == k) {
                    targetUserStart = i;
                    break;
                }
            }
        }
        if (targetUserStart < 0) {
            // K 轮不够，但 history 里有 user：返回所有
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(targetUserStart, history.size()));
    }

    private String trySummarizeRound(List<Message> round) {
        try {
            return summarizer.summarize(SINGLE_ROUND_SUMMARY_PROMPT, round);
        } catch (Exception ex) {
            log.warn("Summarizer LLM failed, falling back: {}", ex.getMessage());
            return null;
        }
    }

    private static long estimateMessagesTokens(List<Message> msgs) {
        long t = 0L;
        for (Message m : msgs) {
            t += ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(m));
        }
        return t;
    }

    private static String truncateToTokens(String text, long maxTokens) {
        if (text == null || text.isEmpty()) return "";
        long maxChars = Math.max(0L, maxTokens) * 4L;
        if (text.length() <= maxChars) return text;
        return text.substring(0, (int) maxChars) + "\n... (truncated)";
    }

    /** 本地兜底摘要 —— LLM 不可用时用首尾 N 行拼接。 */
    static String localHeuristicSummary(List<Message> round) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本地摘要（LLM 不可用时的兜底）】\n");
        for (Message m : round) {
            String text = SessionMessageStore.extractText(m);
            if (text == null || text.isBlank()) continue;
            if (text.length() > 400) text = text.substring(0, 400) + "...";
            String role;
            if (m instanceof UserMessage) role = "用户";
            else if (m instanceof AssistantMessage) role = "助手";
            else if (m instanceof SystemMessage) role = "系统";
            else role = m.getClass().getSimpleName();
            sb.append("- [").append(role).append("] ").append(text.replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }

    // ============== 摘要 LLM 接口 ==============

    public interface SummarizerChatModel {
        String summarize(String systemPrompt, List<Message> history);
    }

    /** 兜底实现：永远可用。 */
    public static final class FallbackSummarizer implements SummarizerChatModel {
        @Override
        public String summarize(String systemPrompt, List<Message> history) {
            return localHeuristicSummary(history);
        }
    }
}

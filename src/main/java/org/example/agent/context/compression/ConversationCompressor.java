package org.example.agent.context.compression;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.memory.MemoryModelGateway;
import org.example.agent.context.memory.MemoryPromptRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话压缩器（part3.md §6.6 压缩策略 §1 + part4.md §7.6 short_term_compress）。
 *
 * <p>新算法：
 * <ol>
 *   <li>取最近 5 轮 = 5 user + 5 assistant 完整记录（含 tool_call / tool_response）。</li>
 *   <li>若总 token 仍超 dynamicReserved，则递减轮数 4 → 3 → 2 → 1。</li>
 *   <li>若 1 轮仍超，对该单轮调用 LLM 摘要压缩（profile {@code short_term_compress}）。</li>
 *   <li>LLM 不可用 → 返回 null，跳过压缩 → 由上层抛 {@link ContextOverflowException} 终止。</li>
 * </ol>
 *
 * <p>§7.5 硬性约束：<b>不</b>启用启发式兜底（删除旧版 {@code FallbackSummarizer}）。
 * LLM 不可用时直接放弃压缩，让溢出异常自然上报。
 */
@Slf4j
@Component
public class ConversationCompressor implements ContextCompressionHook {

    private final MemoryModelGateway gateway;
    private final MemoryPromptRegistry registry;
    private final ContextBudgetPolicy policy;

    public ConversationCompressor() {
        this(null, null, ContextBudgetPolicy.defaultPolicy());
    }

    @Autowired
    public ConversationCompressor(MemoryModelGateway gateway,
                                  MemoryPromptRegistry registry,
                                  ContextBudgetPolicy policy) {
        this.gateway = gateway;
        this.registry = registry;
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
    }

    @Override
    public boolean tryCompress(String executionId, long promptTokens, long effectiveBudget) {
        log.debug("CompressionHook routed through ContextBuilder: executionId={} prompt={}/{}",
                executionId, promptTokens, effectiveBudget);
        return false;
    }

    /**
     * 按 part3.md §6.6 规则 1 加载 messages：
     * <ol>
     *   <li>默认取最近 5 轮。</li>
     *   <li>超预算则 4 → 3 → 2 → 1 递减。</li>
     *   <li>1 轮仍超则对单轮做 LLM 摘要压缩（{@code short_term_compress} profile）。</li>
     *   <li>仍超 → 抛 {@link ContextOverflowException}。</li>
     * </ol>
     *
     * @param history 当前 session 累积的全部消息
     * @param policy  预算策略
     * @param task    当前 AgentTask（用于日志关联，可选）
     * @return 压缩后的消息列表
     */
    public List<Message> loadMessages(List<Message> history, ContextBudgetPolicy policy, AgentTask task) {
        ContextBudgetPolicy p = policy == null ? this.policy : policy;
        return loadMessages(history, p, p.dynamicReserved(), task);
    }

    /**
     * 用自定义 messagesReserved 跑压缩:让 messages 层收敛到不超过 messagesReserved。
     * 由 ContextBuilder 在"总上下文超阈值"时调用,把剩余预算(动态层配额 − 静态层 − 输入)
     * 显式传进来,而不是固定为 dynamicReserved。
     */
    public List<Message> loadMessages(List<Message> history, ContextBudgetPolicy policy,
                                      long messagesReserved, AgentTask task) {
        if (history == null || history.isEmpty()) return new ArrayList<>();
        ContextBudgetPolicy p = policy == null ? this.policy : policy;
        long reserved = Math.max(0L, messagesReserved);
        int startRounds = p.getKeepRecentRounds();

        for (int rounds = startRounds; rounds >= 1; rounds--) {
            List<Message> slice = takeRecentRounds(history, rounds);
            long used = estimateMessagesTokens(slice);
            if (used <= reserved) {
                log.debug("loadMessages: {} rounds used {} <= reserved {}, accepting slice",
                        rounds, used, reserved);
                return slice;
            }
            log.debug("loadMessages: {} rounds used {} > reserved {}, trying fewer rounds",
                    rounds, used, reserved);
        }

        // 1 轮仍超：尝试 LLM 摘要
        List<Message> singleRound = takeRecentRounds(history, 1);
        if (singleRound.isEmpty()) {
            throw new ContextBuilder.ContextOverflowException(
                    "history cannot produce any round",
                    estimateMessagesTokens(history), reserved);
        }
        String summary = trySummarizeRound(singleRound);
        if (summary == null) {
            throw new ContextBuilder.ContextOverflowException(
                    "single-round summary unavailable (memory LLM disabled) — used "
                            + estimateMessagesTokens(singleRound) + " > " + reserved,
                    estimateMessagesTokens(singleRound), reserved);
        }
        summary = truncateToTokens(summary, p.getSummaryTokenCap());
        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage("（以下是 1 轮对话的 LLM 摘要，原始消息因长度超出已折叠）\n" + summary));
        long used = estimateMessagesTokens(compressed);
        log.info("loadMessages: 1-round overflow, summarized to {} tokens (reserved={})", used, reserved);

        if (used > reserved) {
            throw new ContextBuilder.ContextOverflowException(
                    "single round summary exceeds reserved budget: " + used + " > " + reserved,
                    used, reserved);
        }
        return compressed;
    }

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
            return new ArrayList<>(history);
        }
        return new ArrayList<>(history.subList(targetUserStart, history.size()));
    }

    private String trySummarizeRound(List<Message> round) {
        if (gateway == null || !gateway.isAvailable() || registry == null) return null;
        String transcript = renderRound(round);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("conversation", transcript);
        return gateway.call(registry.require("short_term_compress"), vars);
    }

    static String renderRound(List<Message> round) {
        StringBuilder sb = new StringBuilder();
        for (Message m : round) {
            String role;
            if (m instanceof UserMessage) role = "user";
            else if (m instanceof AssistantMessage) role = "assistant";
            else if (m instanceof SystemMessage) role = "system";
            else role = m.getClass().getSimpleName();
            sb.append("[").append(role).append("] ").append(SessionMessageStore.extractText(m)).append("\n\n");
        }
        return sb.toString();
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
}
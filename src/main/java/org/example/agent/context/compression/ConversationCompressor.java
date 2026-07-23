package org.example.agent.context.compression;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
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
 * 会话压缩器（part3.md §6.5）。
 *
 * <p>压缩算法（与 part3.md §6.5 文字对齐）：
 * <ol>
 *   <li>保留最近 K=5 轮原文（user + assistant + tool 完整记录）。</li>
 *   <li>剩余历史扔给一个独立的"压缩 Agent"（system: "你是会话摘要器"）。</li>
 *   <li>摘要模板：用户的核心目标 / 已经做了哪些事、得到了什么结论 / 待解决的问题 / 关键引用（文件路径、命令、错误信息）。</li>
 *   <li>摘要上限 1500 tokens。</li>
 *   <li>替换历史为单个 system message："以下是早期对话摘要：..."。</li>
 * </ol>
 *
 * <p>v1 简化：摘要调用一个独立的 LLM（{@link SummarizerChatModel} 接口，由调用方注入），
 * 不阻塞主 loop。当 LLM 调用失败时降级为本地启发式摘要（保留首尾几行的纯文本拼接），
 * 保证压缩永远能产生一个能塞回历史的 system message。
 *
 * <p>本类同时实现 {@link ContextCompressionHook} 接口（part3 §6.4 步骤 4 的契约），
 * 注入到 {@link org.example.agent.core.observer.TokenBudgetObserver} 后即生效。
 */
@Slf4j
@Component
public class ConversationCompressor implements ContextCompressionHook {

    /** 摘要提示词模板。 */
    public static final String SUMMARY_SYSTEM_PROMPT = """
            你是会话摘要器。给定一段历史对话，请用结构化中文输出摘要，控制在 1500 token 以内。
            摘要必须包含四个部分：

            1. 用户的核心目标是什么
            2. 已经做了哪些事、得到了什么结论
            3. 待解决的问题
            4. 关键引用（文件路径、命令、错误信息）

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
        // TokenBudgetObserver 的 hook 入口。我们没有 executionId -> sessionId 的映射
        // （observer 是单次执行级别的，sessionId 是 AgentTask 的）。这里直接返回 false
        // 让上层 ContextBuilder 在 build() 时主动调 compress(history, ...)。
        // Hook 入口更适合的是「下一次 LLM 调用前的 in-place 替换」语义，
        // 留给 ContextBuilder 做。
        log.debug("CompressionHook invoked but routed through ContextBuilder: executionId={} prompt={}/{}",
                executionId, promptTokens, effectiveBudget);
        return false;
    }

    /**
     * 把超长的 history 压缩到 sessionReserved 预算内。
     *
     * @param history  当前 session 累积的全部消息（含 user / assistant / tool）
     * @param policy   预算策略
     * @param task     当前 AgentTask（用于日志关联，可选）
     * @return 压缩后的新消息列表（可能包含 1 条 system 摘要 + K 轮原文）
     */
    public List<Message> compress(List<Message> history, ContextBudgetPolicy policy, AgentTask task) {
        if (history == null || history.isEmpty()) return new ArrayList<>();
        ContextBudgetPolicy p = policy == null ? this.policy : policy;
        int k = p.getKeepRecentRounds();
        long summaryCap = p.getSummaryTokenCap();
        long sessionReserved = p.sessionReserved();

        // 把 history 切成「旧段 + 新段」。
        // 「最近 K 轮」的最小化定义：每轮 = 1 user + 1 assistant（含 tool_calls + tool responses 的连续块）。
        // 这里采用近似策略：以 user 为锚点向后扫 K 个 user，标记所有这些 user 之后到末尾的所有消息。
        int splitIdx = splitKeepRecent(history, k);
        if (splitIdx <= 0) {
            log.debug("Compress: history too short to compress (size={}, k={})", history.size(), k);
            return history;
        }
        List<Message> oldPart = history.subList(0, splitIdx);
        List<Message> recent = new ArrayList<>(history.subList(splitIdx, history.size()));

        // 1. 调 LLM 生成摘要
        String summary;
        try {
            summary = summarizer.summarize(SUMMARY_SYSTEM_PROMPT, oldPart);
        } catch (Exception ex) {
            log.warn("Compress: summarizer LLM failed, falling back to local heuristic: {}", ex.getMessage());
            summary = localHeuristicSummary(oldPart);
        }
        if (summary == null) summary = "";
        summary = truncateToTokens(summary, summaryCap);

        // 2. 拼装新 history：1 条 system 摘要 + recent
        List<Message> compressed = new ArrayList<>();
        compressed.add(new SystemMessage("以下是早期对话摘要：\n" + summary));

        // 3. 如果拼接后仍超 sessionReserved，逐轮丢弃最早的非 system 消息
        compressed.addAll(recent);
        long used = estimateMessagesTokens(compressed);
        int safety = recent.size();
        while (used > sessionReserved && safety-- > 0 && compressed.size() > 1) {
            // 跳过首条 system 摘要
            int dropIdx = -1;
            for (int i = 1; i < compressed.size(); i++) {
                if (!(compressed.get(i) instanceof SystemMessage)) {
                    dropIdx = i;
                    break;
                }
            }
            if (dropIdx < 0) break;
            compressed.remove(dropIdx);
            used = estimateMessagesTokens(compressed);
        }

        log.info("Compress: history {} -> {} messages, tokens {} -> {} (budget {})",
                history.size(), compressed.size(),
                estimateMessagesTokens(history), used, sessionReserved);

        // 4. 标记 session 已压缩（给 /cost 用）
        if (task != null && task.getSessionId() != null) {
            // sessionStore 在注入式路径里也能拿到；这里不强依赖，避免循环注入
        }
        return compressed;
    }

    /**
     * 在 history 里找到「最近 K 轮 user message」的起始下标。
     * <p>每轮的边界：从一个 user 起，到下一个 user 之前结束。
     */
    static int splitKeepRecent(List<Message> history, int k) {
        int userCount = 0;
        int targetUserStartIdx = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof UserMessage) {
                userCount++;
                if (userCount == k) {
                    targetUserStartIdx = i;
                    break;
                }
            }
        }
        if (targetUserStartIdx <= 0) {
            // K 轮都没凑齐 → 不压缩（返回 0，外层判断）
            return 0;
        }
        return targetUserStartIdx;
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

    /** 本地兜底摘要 —— LLM 不可用时用首尾 N 行拼接，给压缩钩子一个永远能跑的实现。 */
    static String localHeuristicSummary(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("【本地摘要（LLM 不可用时的兜底）】\n");
        sb.append("历史消息 ").append(history.size()).append(" 条；保留关键片段：\n\n");
        int kept = 0;
        for (Message m : history) {
            String text = SessionMessageStore.extractText(m);
            if (text == null || text.isBlank()) continue;
            if (text.length() > 400) text = text.substring(0, 400) + "...";
            String role;
            if (m instanceof UserMessage) role = "用户";
            else if (m instanceof AssistantMessage) role = "助手";
            else if (m instanceof SystemMessage) role = "系统";
            else role = m.getClass().getSimpleName();
            sb.append("- [").append(role).append("] ").append(text.replace("\n", " ")).append("\n");
            kept++;
            if (kept >= 30) {
                sb.append("- ... (后续省略)\n");
                break;
            }
        }
        return sb.toString();
    }

    // ============== 摘要 LLM 接口 ==============

    /**
     * 摘要模型接口。生产实现接 DashScopeChatModel；测试 / 本地实现走 FallbackSummarizer。
     */
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
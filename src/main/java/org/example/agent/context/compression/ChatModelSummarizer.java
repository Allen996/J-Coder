package org.example.agent.context.compression;

import org.example.agent.context.session.SessionMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于真实 ChatModel 的摘要器（part3.md §6.5 规则 2）。
 *
 * <p>独立压缩 Agent：用一个 system prompt（"你是会话摘要器..."） + 把 history 序列化成 user 文本，
 * 调一次 chatModel.call() 拿到摘要。
 *
 * <p>失败兜底：LLM 抛错时返回 null，{@link ConversationCompressor#compress} 会自动降级到
 * 本地启发式摘要，不阻断主流程。
 *
 * <p>Spring 注入：通过 {@code @Primary} 优先于 {@code ConversationCompressor.FallbackSummarizer}，
 * 让 {@code ConversationCompressor} 拿到真实模型。
 */
@Component
@Primary
public class ChatModelSummarizer implements ConversationCompressor.SummarizerChatModel {

    private static final Logger log = LoggerFactory.getLogger(ChatModelSummarizer.class);

    private final ChatModel chatModel;

    @Autowired
    public ChatModelSummarizer(ApplicationContext ctx) {
        ChatModel resolved = null;
        try {
            resolved = ctx.getBean("memoryChatModel", ChatModel.class);
        } catch (Exception ignore) { }
        this.chatModel = resolved;
        if (this.chatModel == null) {
            log.info("ChatModelSummarizer: no memoryChatModel bean available, will fall back to heuristic summary");
        }
    }

    @Override
    public String summarize(String systemPrompt, List<Message> history) {
        if (chatModel == null) return null;
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        // 把 history 折叠成一个 user 消息（避免对话历史里既有 user 又有 assistant 触发多轮格式）
        String userPayload = renderHistoryAsPayload(history);
        messages.add(new UserMessage(userPayload));

        try {
            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                log.warn("Summarizer LLM returned empty response");
                return null;
            }
            String text = response.getResult().getOutput().getText();
            if (text == null) return null;
            return text.trim();
        } catch (Exception ex) {
            log.warn("Summarizer LLM call failed: {}", ex.getMessage());
            return null;
        }
    }

    /** 把 history 渲染成单段文本 payload，方便 LLM 处理。 */
    static String renderHistoryAsPayload(List<Message> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是早期对话记录，请按系统提示词的结构输出摘要。\n\n");
        for (Message m : history) {
            String role;
            if (m instanceof UserMessage) role = "用户";
            else if (m instanceof AssistantMessage) role = "助手";
            else if (m instanceof SystemMessage) role = "系统";
            else role = m.getClass().getSimpleName();
            String text = SessionMessageStore.extractText(m);
            if (text == null) text = "";
            sb.append("[").append(role).append("]\n").append(text).append("\n\n");
        }
        return sb.toString();
    }
}
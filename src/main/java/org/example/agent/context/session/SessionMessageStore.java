package org.example.agent.context.session;

import lombok.Getter;
import lombok.ToString;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单次会话的消息存储（part3.md §6.1 Session Layer "运行时累积"）。
 *
 * <p>职责：
 * <ul>
 *   <li>按 sessionId 隔离消息列表（当前 CLI 永远只有一个 session，但 API 已经按 sessionId 划分）。</li>
 *   <li>提供 addUser / addAssistant / addSystem 写入 API。</li>
 *   <li>提供 snapshot() 给 {@link org.example.agent.context.builder.ContextBuilder} 装配 prompt。</li>
 *   <li>提供 replaceAll() 给压缩钩子整体替换历史。</li>
 *   <li>提供 estimateUsedTokens() 给 {@link org.example.agent.context.budget.ContextBudgetPolicy} 做溢出判断。</li>
 * </ul>
 *
 * <p>并发模型：内部用 {@link CopyOnWriteArrayList}，写时复制 + 读无锁。
 * 配合 part3.md "Session Layer 在 step 间隙触发压缩" 的语义，压缩时一次性
 * 读取快照 + 调用 replaceAll，COW 的开销可以忽略。
 *
 * <p>v1 简化：仅内存存储。Part 4 的 SQLite 持久化会在 store 之外再做一层。
 */
@Component
public class SessionMessageStore {

    @Getter
    @ToString(of = {"sessionId", "size"})
    public static final class Session {
        private final String sessionId;
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private volatile boolean hasCompressed;
        private volatile String summaryText;

        Session(String sessionId) {
            this.sessionId = sessionId;
        }

        public int size() {
            return messages.size();
        }

        public List<Message> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }

        public void add(Message m) {
            if (m != null) messages.add(m);
        }

        public void addAll(List<Message> newMessages) {
            if (newMessages != null && !newMessages.isEmpty()) {
                messages.addAll(newMessages);
            }
        }

        public void replaceAll(List<Message> newMessages) {
            messages.clear();
            if (newMessages != null) messages.addAll(newMessages);
        }

        public void markCompressed(String summaryText) {
            this.hasCompressed = true;
            this.summaryText = summaryText;
        }
    }

    private final java.util.Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    public Session getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        return sessions.computeIfAbsent(sessionId, Session::new);
    }

    public Session get(String sessionId) {
        return sessions.get(sessionId);
    }

    public void clear(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s != null) {
            s.replaceAll(new ArrayList<>());
            s.hasCompressed = false;
            s.summaryText = null;
        }
    }

    // ============ 写入便捷 API ============

    public void addUser(String sessionId, String content) {
        getOrCreate(sessionId).add(new UserMessage(content == null ? "" : content));
    }

    public void addAssistant(String sessionId, String content) {
        getOrCreate(sessionId).add(new AssistantMessage(content == null ? "" : content));
    }

    public void addAssistant(AssistantMessage message) {
        if (message == null) return;
    }

    public void addSystem(String sessionId, String content) {
        getOrCreate(sessionId).add(new SystemMessage(content == null ? "" : content));
    }

    /**
     * 估算已用 token（与 ContextBudgetPolicy.estimateTextTokens 同口径）。
     */
    public long estimateUsedTokens(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s == null) return 0L;
        long total = 0L;
        for (Message m : s.snapshot()) {
            String text = extractText(m);
            total += org.example.agent.context.budget.ContextBudgetPolicy.estimateTextTokens(text);
        }
        return total;
    }

    public static String extractText(Message m) {
        if (m == null) return "";
        if (m instanceof SystemMessage sys) return sys.getText();
        if (m instanceof UserMessage user) return user.getText();
        if (m instanceof AssistantMessage asst) return asst.getText();
        return m.toString();
    }
}
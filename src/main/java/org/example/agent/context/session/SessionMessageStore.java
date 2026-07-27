package org.example.agent.context.session;

import lombok.Getter;
import lombok.ToString;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.memory.MemoryFile;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单次会话的消息存储（part3.md §6.3 + part4.md §7.2 短期记忆）。
 *
 * <p>职责：
 * <ul>
 *   <li>按 sessionId 隔离消息列表（当前 CLI 永远只有一个 session，但 API 已经按 sessionId 划分）。</li>
 *   <li>提供 addUser / addAssistant / addSystem 写入 API。</li>
 *   <li>提供 snapshot() 给 {@link org.example.agent.context.builder.ContextBuilder} 装配 prompt。</li>
 *   <li>提供 replaceAll() 给压缩钩子整体替换历史。</li>
 *   <li>提供 estimateUsedTokens() 给 {@link org.example.agent.context.budget.ContextBudgetPolicy} 做溢出判断。</li>
 *   <li>每个 session 同步追加到 {@code .agent/sessions/{sessionId}/short-term.md}（MD + YAML frontmatter）；
 *       每条 message 一段，含 role / timestamp / content / message_id；tool_call 与 tool_response 邻段配对共享同一 message_id。</li>
 * </ul>
 *
 * <p>并发模型：内部用 {@link CopyOnWriteArrayList}，写时复制 + 读无锁。
 * 配合 part3.md "Session Layer 在 step 间隙触发压缩" 的语义，压缩时一次性
 * 读取快照 + 调用 replaceAll，COW 的开销可以忽略。
 *
 * <p>持久化策略：append-only 增量追加（每次 addUser/addAssistant 同步写一段到磁盘）；
 * 整体落盘用 temp + rename。
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

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path sessionsRoot;

    public SessionMessageStore() {
        this(Paths.get(".agent", "sessions"));
    }

    public SessionMessageStore(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    public Session getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        return sessions.computeIfAbsent(sessionId, s -> {
            Session session = new Session(s);
            loadFromDisk(session);
            return session;
        });
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
        Message m = new UserMessage(content == null ? "" : content);
        getOrCreate(sessionId).add(m);
        appendSection(sessionId, m, "user");
    }

    public void addAssistant(String sessionId, String content) {
        Message m = new AssistantMessage(content == null ? "" : content);
        getOrCreate(sessionId).add(m);
        appendSection(sessionId, m, "assistant");
    }

    public void addSystem(String sessionId, String content) {
        Message m = new SystemMessage(content == null ? "" : content);
        getOrCreate(sessionId).add(m);
        appendSection(sessionId, m, "system");
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
            total += ContextBudgetPolicy.estimateTextTokens(text);
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

    // ============ 持久化：MD + YAML frontmatter ============

    /** 当前 session 落盘路径。 */
    public Path pathFor(String sessionId) {
        return sessionsRoot.resolve(safeSessionId(sessionId)).resolve("short-term.md");
    }

    private void loadFromDisk(Session session) {
        Path p = pathFor(session.getSessionId());
        if (!Files.exists(p)) return;
        String raw = MemoryFile.readOrBackup(p);
        if (raw == null || raw.isBlank()) return;
        String body = stripFrontmatter(raw);
        for (String section : splitSections(body)) {
            ParsedSection ps = parseSection(section);
            if (ps == null) continue;
            Message m = rebuildMessage(ps);
            if (m != null) session.messages.add(m);
        }
    }

    private void appendSection(String sessionId, Message m, String role) {
        Path p = pathFor(sessionId);
        if (p == null) return;
        try {
            Files.createDirectories(p.getParent());
        } catch (Exception ignore) { return; }
        if (!Files.exists(p)) {
            // 首次写入：先写 frontmatter + 当前段
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("schema", "1");
            fm.put("sessionId", sessionId);
            fm.put("createdAt", Instant.now().toString());
            String body = "\n" + renderSection(m, role, Instant.now().toString());
            MemoryFile.writeAtomic(p, fm, body);
        } else {
            String section = renderSection(m, role, Instant.now().toString());
            MemoryFile.appendSection(p, section);
        }
    }

    static String renderSection(Message m, String role, String timestamp) {
        String mid = UUID.randomUUID().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("\n## message\n");
        sb.append("- message_id: ").append(mid).append("\n");
        sb.append("- role: ").append(role).append("\n");
        sb.append("- timestamp: ").append(timestamp).append("\n");
        sb.append("- content: |\n");
        for (String line : extractText(m).split("\n", -1)) {
            sb.append("    ").append(line).append("\n");
        }
        return sb.toString();
    }

    /** 按 "## message" 切出每个段落。 */
    static List<String> splitSections(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        String[] parts = body.split("(?m)^## message\\s*$");
        for (String p : parts) {
            if (p.isBlank()) continue;
            out.add(p);
        }
        return out;
    }

    static ParsedSection parseSection(String section) {
        if (section == null) return null;
        ParsedSection ps = new ParsedSection();
        String[] lines = section.split("\n", -1);
        boolean inContent = false;
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            String s = line.stripLeading();
            if (!inContent) {
                if (s.startsWith("- message_id:")) {
                    ps.messageId = s.substring("- message_id:".length()).strip();
                } else if (s.startsWith("- role:")) {
                    ps.role = s.substring("- role:".length()).strip();
                } else if (s.startsWith("- timestamp:")) {
                    ps.timestamp = s.substring("- timestamp:".length()).strip();
                } else if (s.startsWith("- content:")) {
                    inContent = true;
                }
            } else {
                content.append(line).append("\n");
            }
        }
        ps.content = content.toString().strip();
        if (ps.role == null) return null;
        return ps;
    }

    static Message rebuildMessage(ParsedSection ps) {
        return switch (ps.role) {
            case "user" -> new UserMessage(ps.content);
            case "assistant" -> new AssistantMessage(ps.content);
            case "system" -> new SystemMessage(ps.content);
            default -> null;
        };
    }

    static String stripFrontmatter(String raw) {
        if (raw == null || raw.isBlank()) return "";
        if (!raw.startsWith("---")) return raw;
        int second = raw.indexOf("\n---", 3);
        if (second < 0) return raw;
        int bodyStart = raw.indexOf('\n', second + 4);
        if (bodyStart < 0) return "";
        return raw.substring(bodyStart + 1);
    }

    private static String safeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "default";
        return sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    static final class ParsedSection {
        String messageId;
        String role;
        String timestamp;
        String content;
    }
}

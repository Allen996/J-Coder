package org.example.agent.context.memory;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中期记忆存储（part3.md §6.3 / part4.md §7.2）。
 *
 * <p>每个 session 一份独立 Markdown 文件 {@code .agent/sessions/{sessionId}/mid-term.md}。
 * 头部 YAML frontmatter 记录元数据（schema / sessionId / createdAt / updatedAt），
 * 正文固定结构包含：{@code session_goal} / {@code completed} / {@code decisions} / {@code lessons} / {@code pending_todos}。
 *
 * <p>更新时机：每轮对话结束后增量更新；会话结束时整体重生成。
 * 消费：memory_index 隐式按相关性匹配，默认返回最相关 5 条；ContextBuilder 装配时调用。
 *
 * <p>v1 简化：内存缓存最新值；不动 LLM 总结的语义接口（{@link #updateMidTerm} / {@link #loadOrEmpty}），
 * 由后续 Part 4 完整实现填 LLM 调用。
 */
@Component
public class MidTermStore {

    @Getter
    public static final class MidTerm {
        private final String sessionId;
        private final String sessionGoal;
        private final String completed;
        private final String decisions;
        private final String lessons;
        private final String pendingTodos;
        private final Instant updatedAt;

        public MidTerm(String sessionId, String sessionGoal, String completed,
                       String decisions, String lessons, String pendingTodos, Instant updatedAt) {
            this.sessionId = sessionId;
            this.sessionGoal = sessionGoal == null ? "" : sessionGoal;
            this.completed = completed == null ? "" : completed;
            this.decisions = decisions == null ? "" : decisions;
            this.lessons = lessons == null ? "" : lessons;
            this.pendingTodos = pendingTodos == null ? "" : pendingTodos;
            this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        }

        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# session_goal\n").append(sessionGoal).append("\n\n");
            sb.append("# completed\n").append(completed).append("\n\n");
            sb.append("# decisions\n").append(decisions).append("\n\n");
            sb.append("# lessons\n").append(lessons).append("\n\n");
            sb.append("# pending_todos\n").append(pendingTodos).append("\n");
            return sb.toString();
        }
    }

    private final Map<String, MidTerm> cache = new ConcurrentHashMap<>();
    private final Path sessionsRoot;

    public MidTermStore() {
        this(Paths.get(".agent", "sessions"));
    }

    public MidTermStore(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
    }

    /** sessionId 决定 .agent/sessions/{sessionId}/mid-term.md 路径。 */
    public Path pathFor(String sessionId) {
        return sessionsRoot.resolve(safeSessionId(sessionId)).resolve("mid-term.md");
    }

    /** 加载指定 session 的中期记忆；缺失或解析失败时返回空 MidTerm。 */
    public MidTerm loadOrEmpty(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        MidTerm cached = cache.get(sessionId);
        if (cached != null) return cached;
        Path p = pathFor(sessionId);
        if (!Files.exists(p)) return null;
        String raw = MemoryFile.readOrBackup(p);
        Map<String, Object> fm = Frontmatter.parse(raw);
        Instant updatedAt = fm.get("updatedAt") != null ? Instant.parse(fm.get("updatedAt").toString()) : Instant.now();
        MidTerm parsed = parseBody(raw, sessionId, updatedAt);
        if (parsed != null) {
            cache.put(sessionId, parsed);
        }
        return parsed;
    }

    /** 更新并落盘；增量合并（v1 简化：直接整体替换）。 */
    public void updateMidTerm(String sessionId, MidTerm midTerm) {
        if (sessionId == null || midTerm == null) return;
        cache.put(sessionId, midTerm);
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("sessionId", sessionId);
        fm.put("updatedAt", Instant.now().toString());
        try {
            Files.createDirectories(pathFor(sessionId).getParent());
        } catch (IOException ignore) { }
        MemoryFile.writeAtomic(pathFor(sessionId), fm, midTerm.toMarkdown());
    }

    /** 从全文解析 5 个分段（v1 简化：用 # 标题分段）。 */
    static MidTerm parseBody(String raw, String sessionId, Instant updatedAt) {
        if (raw == null || raw.isBlank()) return null;
        String body = stripFrontmatter(raw);
        String[] sections = new String[] { "session_goal", "completed", "decisions", "lessons", "pending_todos" };
        String[] values = new String[5];
        for (int i = 0; i < sections.length; i++) {
            int start = body.indexOf("# " + sections[i]);
            if (start < 0) {
                values[i] = "";
                continue;
            }
            int contentStart = body.indexOf('\n', start);
            if (contentStart < 0) { values[i] = ""; continue; }
            int end = body.length();
            for (int j = i + 1; j < sections.length; j++) {
                int next = body.indexOf("# " + sections[j], contentStart);
                if (next >= 0) { end = next; break; }
            }
            values[i] = body.substring(contentStart + 1, end).strip();
        }
        return new MidTerm(sessionId, values[0], values[1], values[2], values[3], values[4], updatedAt);
    }

    private static String stripFrontmatter(String raw) {
        if (raw == null) return "";
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
}

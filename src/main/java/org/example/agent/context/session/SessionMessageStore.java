package org.example.agent.context.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
 * 单次会话的消息存储（part3.md §6.3 + part4.md §7.2 / §7.4 短期记忆）。
 *
 * <p>职责：
 * <ul>
 *   <li>按 sessionId 隔离消息列表（当前 CLI 永远只有一个 session，但 API 已经按 sessionId 划分）。</li>
 *   <li>提供 addUser / addAssistant / addSystem 写入 API；写入同时记录到内存列表。</li>
 *   <li>提供 snapshot() 给 {@link org.example.agent.context.builder.ContextBuilder} 装配 prompt。</li>
 *   <li>提供 replaceAll() 给压缩钩子整体替换历史。</li>
 *   <li>提供 estimateUsedTokens() 给 {@link org.example.agent.context.budget.ContextBudgetPolicy} 做溢出判断。</li>
 *   <li>每个 session 持久化为 {@code .agent/sessions/{sessionId}/short-term.json}，结构对齐 Spring AI Message 列表：
 *     assistant 消息带 {@code tool_calls} 字段（每项含 id / name / args），tool 消息带 {@code tool_call_id} 与 content。
 *     单条工具结果超过 {@link #toolResultInlineLimit}（默认 2 KB）时，外置到 {@code tool-results/{tool_call_id}.json}，
 *     主对话流 JSON 中只保留 {@code tool_result_ref} 引用，反序列化时自动加载回填。</li>
 * </ul>
 *
 * <p>并发模型：内部用 {@link CopyOnWriteArrayList}，写时复制 + 读无锁。
 *
 * <p>持久化策略：append-only 增量追加（每次 addUser/addAssistant 同步写一条记录到磁盘）；
 * 整体落盘用 temp + rename（{@link #persistFull}）。
 */
@Component
public class SessionMessageStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageStore.class);

    /** 单条工具结果内联阈值（bytes）；超过则外置。 */
    public static final int DEFAULT_TOOL_RESULT_INLINE_LIMIT = 2 * 1024;
    public static final String SCHEMA_VERSION = "2";

    @Getter
    @ToString(of = {"sessionId", "size", "compressionCount"})
    public static final class Session {
        private final String sessionId;
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private volatile boolean hasCompressed;
        private volatile String summaryText;
        private final java.util.concurrent.atomic.AtomicInteger compressionCount = new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicLong lastCompressionBeforeUsed = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong lastCompressionAfterUsed = new java.util.concurrent.atomic.AtomicLong(0);

        Session(String sessionId) {
            this.sessionId = sessionId;
        }

        public int compressionCount() {
            return compressionCount.get();
        }

        public long lastCompressionBeforeUsed() {
            return lastCompressionBeforeUsed.get();
        }

        public long lastCompressionAfterUsed() {
            return lastCompressionAfterUsed.get();
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

        /** 供自动压缩路径调用 —— 记录触发次数与触发前后 token 数,便于 /context 与 eval 解析。 */
        public void recordAutoCompression(long beforeUsed, long afterUsed) {
            this.compressionCount.incrementAndGet();
            this.lastCompressionBeforeUsed.set(Math.max(0L, beforeUsed));
            this.lastCompressionAfterUsed.set(Math.max(0L, afterUsed));
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path sessionsRoot;
    private final int toolResultInlineLimit;
    private final ObjectMapper mapper;

    public SessionMessageStore() {
        this(Paths.get(".agent", "sessions"), DEFAULT_TOOL_RESULT_INLINE_LIMIT);
    }

    public SessionMessageStore(Path sessionsRoot) {
        this(sessionsRoot, DEFAULT_TOOL_RESULT_INLINE_LIMIT);
    }

    public SessionMessageStore(Path sessionsRoot, int toolResultInlineLimit) {
        this.sessionsRoot = sessionsRoot;
        this.toolResultInlineLimit = Math.max(256, toolResultInlineLimit);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
        appendRecord(sessionId, "user", content, null, null, null);
    }

    public void addAssistant(String sessionId, String content) {
        Message m = new AssistantMessage(content == null ? "" : content);
        getOrCreate(sessionId).add(m);
        appendRecord(sessionId, "assistant", content, null, null, null);
    }

    public void addSystem(String sessionId, String content) {
        Message m = new SystemMessage(content == null ? "" : content);
        getOrCreate(sessionId).add(m);
        appendRecord(sessionId, "system", content, null, null, null);
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

    // ============ 持久化：JSON ============

    /** 当前 session 主对话流落盘路径。 */
    public Path pathFor(String sessionId) {
        return sessionsRoot.resolve(safeSessionId(sessionId)).resolve("short-term.json");
    }

    /** 外置工具结果路径。 */
    public Path toolResultPath(String sessionId, String toolCallId) {
        return sessionsRoot.resolve(safeSessionId(sessionId))
                .resolve("tool-results").resolve(safeFileName(toolCallId) + ".json");
    }

    private void loadFromDisk(Session session) {
        Path p = pathFor(session.getSessionId());
        if (!Files.exists(p)) return;
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return;
            ShortTermFile data = mapper.readValue(raw, ShortTermFile.class);
            if (data == null || data.messages == null) return;
            for (Record r : data.messages) {
                Message m = rebuildMessage(r);
                if (m != null) session.messages.add(m);
            }
        } catch (Exception ex) {
            log.warn("SessionMessageStore load failed for {}: {}", p, ex.getMessage());
        }
    }

    private void appendRecord(String sessionId, String role, String content,
                              String toolCallId, List<ToolCallRecord> toolCalls, String toolResultRef) {
        Path p = pathFor(sessionId);
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException ignore) { return; }
        Record r = new Record();
        r.role = role;
        r.timestamp = Instant.now().toString();
        r.content = content == null ? "" : content;
        r.toolCallId = toolCallId;
        r.toolCalls = toolCalls;
        r.toolResultRef = toolResultRef;
        if (!Files.exists(p)) {
            // 首次写入：写完整文件
            ShortTermFile f = new ShortTermFile();
            f.schema = SCHEMA_VERSION;
            f.sessionId = sessionId;
            f.createdAt = r.timestamp;
            List<Record> list = new ArrayList<>();
            list.add(r);
            f.messages = list;
            persistFull(p, f);
        } else {
            // 增量追加：读已有 messages + 本条 → 整体重写
            try {
                ShortTermFile existing = mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), ShortTermFile.class);
                if (existing.messages == null) existing.messages = new ArrayList<>();
                existing.messages.add(r);
                existing.updatedAt = r.timestamp;
                persistFull(p, existing);
            } catch (Exception ex) {
                log.warn("SessionMessageStore append failed for {}: {}", p, ex.getMessage());
            }
        }
    }

    /** 整文件原子写：临时文件 + rename。 */
    private void persistFull(Path p, ShortTermFile data) {
        try {
            Path tmp = Files.createTempFile(p.getParent(), "short-term.", ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            try {
                Files.move(tmp, p,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, p,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.warn("SessionMessageStore persistFull failed for {}: {}", p, ex.getMessage());
        }
    }

    /**
     * 写入外置工具结果（当单条 tool 返回内容超过 {@link #toolResultInlineLimit}）。
     * 返回相对路径（如 {@code .agent/sessions/{id}/tool-results/{callId}.json}）。
     */
    public String externalizeToolResult(String sessionId, String toolCallId, String content) {
        if (toolCallId == null || toolCallId.isBlank() || content == null) return null;
        Path p = toolResultPath(sessionId, toolCallId);
        try {
            Files.createDirectories(p.getParent());
            ToolResultRecord rec = new ToolResultRecord();
            rec.toolCallId = toolCallId;
            rec.content = content;
            rec.size = content.getBytes(StandardCharsets.UTF_8).length;
            mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), rec);
            return relativeToolResultPath(sessionId, toolCallId);
        } catch (IOException ex) {
            log.warn("externalizeToolResult failed for {}: {}", p, ex.getMessage());
            return null;
        }
    }

    /** 读外置工具结果内容；缺失返回 null。 */
    public String loadToolResult(String sessionId, String toolCallId) {
        Path p = toolResultPath(sessionId, toolCallId);
        if (!Files.exists(p)) return null;
        try {
            ToolResultRecord rec = mapper.readValue(p.toFile(), ToolResultRecord.class);
            return rec == null ? null : rec.content;
        } catch (IOException ex) {
            log.warn("loadToolResult failed for {}: {}", p, ex.getMessage());
            return null;
        }
    }

    private String relativeToolResultPath(String sessionId, String toolCallId) {
        return ".agent/sessions/" + safeSessionId(sessionId)
                + "/tool-results/" + safeFileName(toolCallId) + ".json";
    }

    static Message rebuildMessage(Record r) {
        if (r == null || r.role == null) return null;
        return switch (r.role) {
            case "user" -> new UserMessage(r.content == null ? "" : r.content);
            case "assistant" -> new AssistantMessage(r.content == null ? "" : r.content);
            case "system" -> new SystemMessage(r.content == null ? "" : r.content);
            default -> null;
        };
    }

    private static String safeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "default";
        return sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "anon";
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public Path sessionsRoot() { return sessionsRoot; }
    public int toolResultInlineLimit() { return toolResultInlineLimit; }

    // ============ JSON DTO（Jackson 序列化形态）============

    public static final class ShortTermFile {
        public String schema = SCHEMA_VERSION;
        public String sessionId;
        public String createdAt;
        public String updatedAt;
        public List<Record> messages;
    }

    public static final class Record {
        public String role;            // user | assistant | system | tool
        public String content;
        public String timestamp;
        public String messageId;       // 生成时分配，用于跨 record 关联
        public List<ToolCallRecord> toolCalls;     // assistant 专用
        public String toolCallId;      // tool 专用，对应 assistant.tool_calls[].id
        public String toolResultRef;   // tool 专用，外置引用（超过 inline 阈值时使用）
        public Integer size;           // 原始 content 字节数（外置时填）
    }

    public static final class ToolCallRecord {
        public String id;
        public String name;
        public Map<String, Object> args;
    }

    public static final class ToolResultRecord {
        public String toolCallId;
        public String content;
        public Integer size;
        public String savedAt;
    }
}
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 单次会话的消息存储（part3.md §6.3 + part4.md §7.2 / §7.4 短期记忆 + 中期记忆合并形态）。
 *
 * <p><b>两文件职责</b>：
 * <ul>
 *   <li>{@code .agent/sessions/{sessionId}/short-term.json} —— append-only 工作日志。
 *     一旦写入永不修改、不会被压缩回写覆盖、不会被撤销删除；只用于审计与回滚复原。</li>
 *   <li>{@code .agent/sessions/{sessionId}/mid-term.json}   —— 当前压缩后窗口的快照。
 *     触发压缩（{@link #replaceWindow}）时由 caller 调入新消息列表 → 原子替换（tmp + rename）。
 *     启动期若缺失或被 worklog 中的压缩事件超越，按 §7.2 恢复策略从 worklog 重建。</li>
 * </ul>
 *
 * <p><b>消息种类</b>：
 * <ul>
 *   <li>短期 worklog 接收所有角色（{@code user | assistant | tool | system | meta}）；
 *     其中 {@code meta} 仅记录到 worklog，不进入 {@link Session#messages}（不参与上下文装配）。</li>
 *   <li>中期窗口序列化时第一行可以是 {@code [meta]} 压缩元信息（来自 {@link CompressionInfo}），
 *     其余为常规消息。{@link Session#messages} 不持有 {@code [meta]}，
 *     渲染到上下文时由 {@link #renderMidTermForContext(String)} 自行拼接。</li>
 * </ul>
 *
 * <p><b>写盘顺序契约</b>：
 * <ol>
 *   <li>压缩触发时，{@link #addMeta(String, String)} 先写 worklog。</li>
 *   <li>{@link #replaceWindow(String, java.util.List, CompressionInfo)} 替换内存 + 原子写 mid-term。</li>
 *   <li>若步骤 1 成功、步骤 2 中途崩溃，启动期 {@link #getOrCreate(String)} 检测到 mid-term 落后于
 *     worklog 中的最新 {@code [meta] auto-compress} 事件 → 自动从 worklog 重建。</li>
 * </ol>
 *
 * <p><b>并发</b>：{@link Session#messages} 为 {@link CopyOnWriteArrayList}，
 * 替换窗口的非原子操作由 {@code syncCompressMessages} 单线程路径保证。
 */
@Component
public class SessionMessageStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageStore.class);

    /** 单条工具结果内联阈值（bytes）；超过则外置。 */
    public static final int DEFAULT_TOOL_RESULT_INLINE_LIMIT = 2 * 1024;

    /** JSON schema 版本 —— 3: 加入 mid-term.json、kind 字段、role=meta。 */
    public static final String SCHEMA_VERSION = "3";

    public static final String KIND_SHORT_TERM = "short-term";
    public static final String KIND_MID_TERM   = "mid-term";

    public static final String ROLE_USER      = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM    = "system";
    public static final String ROLE_TOOL      = "tool";
    public static final String ROLE_META      = "meta";

    /** worklog 软上限 —— 超过时仅 warn，不截断。审计完整性优先。 */
    public static final long WORKLOG_SOFT_CAP = 10_000L;

    /** 启动恢复默认取最近多少轮对话（与 {@code ContextBudgetPolicy.keepRecentRounds} 对齐）。 */
    public static final int DEFAULT_KEEP_RECENT_ROUNDS = 5;

    /** 压缩元事件标签（用于 {@link #addMeta(String, String)}）。 */
    public static final String META_TAG_AUTO_COMPRESS    = "auto-compress";
    public static final String META_TAG_STARTUP_RECOVER  = "startup-recover";
    public static final String META_TAG_MANUAL_FLUSH     = "manual-flush";
    public static final String META_TAG_WINDOW_REPLACE   = "window-replace";

    // ====================== 会话状态 ======================

    @Getter
    @ToString(of = {"sessionId", "size", "compressionCount"})
    public static final class Session {
        private final String sessionId;
        private final List<Message> messages = new CopyOnWriteArrayList<>();
        private volatile boolean hasCompressed;
        private volatile String summaryText;
        private final AtomicInteger compressionCount = new AtomicInteger(0);
        private final AtomicLong lastCompressionBeforeUsed = new AtomicLong(0);
        private final AtomicLong lastCompressionAfterUsed = new AtomicLong(0);

        Session(String sessionId) {
            this.sessionId = sessionId;
        }

        public int compressionCount() { return compressionCount.get(); }
        public long lastCompressionBeforeUsed() { return lastCompressionBeforeUsed.get(); }
        public long lastCompressionAfterUsed() { return lastCompressionAfterUsed.get(); }

        public int size() { return messages.size(); }

        public List<Message> snapshot() {
            return Collections.unmodifiableList(new ArrayList<>(messages));
        }

        public void add(Message m) { if (m != null) messages.add(m); }
        public void addAll(List<Message> newMessages) {
            if (newMessages != null && !newMessages.isEmpty()) messages.addAll(newMessages);
        }

        public void replaceAll(List<Message> newMessages) {
            messages.clear();
            if (newMessages != null) messages.addAll(newMessages);
        }

        public void markCompressed(String summaryText) {
            this.hasCompressed = true;
            this.summaryText = summaryText;
        }

        public void recordAutoCompression(long beforeUsed, long afterUsed) {
            this.compressionCount.incrementAndGet();
            this.lastCompressionBeforeUsed.set(Math.max(0L, beforeUsed));
            this.lastCompressionAfterUsed.set(Math.max(0L, afterUsed));
        }
    }

    /**
     * 压缩元信息 —— {@link #replaceWindow} 写入 mid-term.json 第一行 {@code [meta]} 记录。
     * 描述压缩或窗口替换发生的原因、token 变化、轮数。
     */
    @Getter
    public static final class CompressionInfo {
        /** 标签：{@link #META_TAG_AUTO_COMPRESS} / {@link #META_TAG_STARTUP_RECOVER} / {@link #META_TAG_MANUAL_FLUSH} / {@link #META_TAG_WINDOW_REPLACE} */
        private final String tag;
        /** 压缩前 token 数。 */
        private final long beforeUsed;
        /** 压缩后 token 数。 */
        private final long afterUsed;
        /** 本次保留的轮数（0 表示未按轮裁剪）。 */
        private final int roundsKept;
        /** 额外说明，可空。 */
        private final String note;

        public CompressionInfo(String tag, long beforeUsed, long afterUsed, int roundsKept, String note) {
            this.tag = tag == null ? META_TAG_AUTO_COMPRESS : tag;
            this.beforeUsed = Math.max(0L, beforeUsed);
            this.afterUsed = Math.max(0L, afterUsed);
            this.roundsKept = Math.max(0, roundsKept);
            this.note = note == null ? "" : note;
        }

        /** 渲染为单行文本 —— 写入 mid-term.json 第一条 {@code [meta]} 记录的 content。 */
        public String toLine() {
            StringBuilder sb = new StringBuilder("[");
            sb.append(tag).append("] ");
            sb.append(beforeUsed).append("\u2192").append(afterUsed).append(" tokens");
            if (roundsKept > 0) sb.append(" (rounds=").append(roundsKept).append(")");
            if (!note.isBlank()) sb.append(" ").append(note);
            return sb.toString();
        }

        public static CompressionInfo auto(long beforeUsed, long afterUsed, int roundsKept) {
            return new CompressionInfo(META_TAG_AUTO_COMPRESS, beforeUsed, afterUsed, roundsKept, "");
        }

        public static CompressionInfo startupRecover(long beforeUsed, long afterUsed, int roundsKept, String note) {
            return new CompressionInfo(META_TAG_STARTUP_RECOVER, beforeUsed, afterUsed, roundsKept, note);
        }

        public static CompressionInfo manualFlush(long beforeUsed, long afterUsed, int roundsKept) {
            return new CompressionInfo(META_TAG_MANUAL_FLUSH, beforeUsed, afterUsed, roundsKept, "");
        }

        public static CompressionInfo windowReplace(long beforeUsed, long afterUsed, String note) {
            return new CompressionInfo(META_TAG_WINDOW_REPLACE, beforeUsed, afterUsed, 0, note);
        }
    }

    // ====================== 文件 / DTO ======================

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Path sessionsRoot;
    private final int toolResultInlineLimit;
    private final int keepRecentRounds;
    private final ObjectMapper mapper;

    /** 暴露给 SessionCompressor 共用同一个 mapper（避免重复创建）。 */
    public ObjectMapper mapper() {
        return mapper;
    }

    public SessionMessageStore() {
        this(Paths.get(".agent", "sessions"), DEFAULT_TOOL_RESULT_INLINE_LIMIT, DEFAULT_KEEP_RECENT_ROUNDS);
    }

    public SessionMessageStore(Path sessionsRoot) {
        this(sessionsRoot, DEFAULT_TOOL_RESULT_INLINE_LIMIT, DEFAULT_KEEP_RECENT_ROUNDS);
    }

    public SessionMessageStore(Path sessionsRoot, int toolResultInlineLimit) {
        this(sessionsRoot, toolResultInlineLimit, DEFAULT_KEEP_RECENT_ROUNDS);
    }

    public SessionMessageStore(Path sessionsRoot, int toolResultInlineLimit, int keepRecentRounds) {
        this.sessionsRoot = sessionsRoot;
        this.toolResultInlineLimit = Math.max(256, toolResultInlineLimit);
        this.keepRecentRounds = Math.max(1, keepRecentRounds);
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public Session getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        return sessions.computeIfAbsent(sessionId, sid -> {
            Session session = new Session(sid);
            loadOnCreate(session);
            return session;
        });
    }

    public Session get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 清空内存会话（mid-term.json 与 short-term.json 不动）。
     * 调用方负责根据需要删除磁盘文件（{@link #deleteSessionFiles(String)}）。
     */
    public void clear(String sessionId) {
        Session s = sessions.get(sessionId);
        if (s != null) {
            s.replaceAll(new ArrayList<>());
            s.hasCompressed = false;
            s.summaryText = null;
        }
    }

    /** 删除 session 关联的所有磁盘文件（mid-term.json + short-term.json + tool-results/）。 */
    public void deleteSessionFiles(String sessionId) {
        Path dir = sessionsRoot.resolve(safeSessionId(sessionId));
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignore) {}
            });
        } catch (IOException ex) {
            log.warn("deleteSessionFiles failed for {}: {}", dir, ex.getMessage());
        }
    }

    // ============ 写入便捷 API（追加到 worklog + 内存） ============

    public void addUser(String sessionId, String content) {
        appendToWorklog(sessionId, ROLE_USER, content, null, null, null);
        getOrCreate(sessionId).add(content == null ? new UserMessage("") : new UserMessage(content));
    }

    public void addAssistant(String sessionId, String content) {
        appendToWorklog(sessionId, ROLE_ASSISTANT, content, null, null, null);
        getOrCreate(sessionId).add(content == null ? new AssistantMessage("") : new AssistantMessage(content));
    }

    public void addSystem(String sessionId, String content) {
        appendToWorklog(sessionId, ROLE_SYSTEM, content, null, null, null);
        getOrCreate(sessionId).add(content == null ? new SystemMessage("") : new SystemMessage(content));
    }

    /**
     * 写一条 {@code [meta]} 事件到 worklog（<b>不</b>写入 {@link Session#messages}，不参与上下文装配）。
     * 用于记录压缩、启动恢复、/memory flush 等元事件。
     */
    public void addMeta(String sessionId, String content) {
        if (content == null) return;
        appendToWorklog(sessionId, ROLE_META, content, null, null, null);
    }

    /**
     * 替换当前会话窗口：内存替换 + 原子写 mid-term.json（tmp + rename）。
     *
     * <p>若 {@code info != null}，mid-term.json 的首条记录为该 info 渲染的 {@code [meta]} 行；
     * 后续为 {@code messages} 转 Record 的结果。
     *
     * <p>写盘顺序契约：caller 应已先调 {@link #addMeta(String, String)} 把元事件写入 worklog。
     * 本方法本身<b>不</b>写 worklog（避免双重审计）。
     */
    public void replaceWindow(String sessionId, List<Message> newMessages, CompressionInfo info) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        Session session = getOrCreate(sessionId);
        long before = estimateMessagesTokens(session.snapshot());
        session.replaceAll(newMessages == null ? List.of() : newMessages);
        CompressionInfo effective = info == null
                ? CompressionInfo.windowReplace(before, estimateMessagesTokens(session.snapshot()), "")
                : info;
        persistMidTerm(sessionId, newMessages == null ? List.of() : newMessages, effective);
        // 仅当 info 非 null 时(真正压缩事件)才更新 Session 的压缩计数。
        if (info != null) {
            session.markCompressed(effective.toLine());
            session.recordAutoCompression(effective.beforeUsed, effective.afterUsed);
        }
        enforceWorklogSoftCap(sessionId);
    }

    /**
     * 把当前 {@link Session#messages} 强制落盘到 mid-term.json（graceful shutdown 用）。
     * <b>不</b>触发压缩计数更新与 Session.markCompressed。
     */
    public void persistMidTerm(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        Session session = sessions.get(sessionId);
        List<Message> current = session == null ? List.of() : session.snapshot();
        persistMidTerm(sessionId, current,
                CompressionInfo.windowReplace(0, estimateMessagesTokens(current), "manual persistMidTerm"));
    }

    private void persistMidTerm(String sessionId, List<Message> messages, CompressionInfo info) {
        Path target = midTermPath(sessionId);
        Path dir = target.getParent();
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            log.warn("persistMidTerm: mkdir {} failed: {}", dir, ex.getMessage());
            return;
        }
        List<Record> records = new ArrayList<>(messages.size() + 1);
        if (info != null) {
            records.add(metaRecord(info));
        }
        for (Message m : messages) {
            records.add(recordFromMessage(m));
        }
        MemoryFile envelope = new MemoryFile();
        envelope.schema = SCHEMA_VERSION;
        envelope.kind = KIND_MID_TERM;
        envelope.sessionId = sessionId;
        Instant now = Instant.now();
        if (envelope.createdAt == null) envelope.createdAt = now.toString();
        envelope.updatedAt = now.toString();
        envelope.messages = records;

        atomicWriteJson(target, envelope);
        log.debug("persistMidTerm: session={} records={} ({}+meta) bytes={}",
                sessionId, records.size(), messages.size(), safeSize(target));
    }

    /** 从 mid-term.json 读取当前窗口（已过滤 {@code [meta]}，只返回 Spring AI Message 列表）。 */
    public List<Message> loadMidTermOrEmpty(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        Path p = midTermPath(sessionId);
        if (!Files.exists(p)) return List.of();
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return List.of();
            MemoryFile f = mapper.readValue(raw, MemoryFile.class);
            if (f == null || f.messages == null) return List.of();
            // 一致性：被 worklog 中的 [meta] auto-compress 事件超越 → 视为不可信
            if (isStaleRelativeToWorklog(f, sessionId)) {
                log.warn("loadMidTerm: {} stale relative to worklog; will be rebuilt on next recovery", p);
                return List.of();
            }
            List<Message> out = new ArrayList<>(f.messages.size());
            for (Record r : f.messages) {
                Message m = rebuildMessage(r);
                if (m != null) out.add(m);
            }
            return out;
        } catch (Exception ex) {
            log.warn("loadMidTermOrEmpty failed for {}: {}", p, ex.getMessage());
            return List.of();
        }
    }

    /** 渲染 mid-term 窗口为可注入上下文的文本（含首行 {@code [meta]} 信息 + 消息列表）。 */
    public String renderMidTermForContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        Path p = midTermPath(sessionId);
        if (!Files.exists(p)) return "";
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return "";
            MemoryFile f = mapper.readValue(raw, MemoryFile.class);
            if (f == null || f.messages == null) return "";
            return renderMidTermAsText(f);
        } catch (Exception ex) {
            log.warn("renderMidTermForContext failed for {}: {}", p, ex.getMessage());
            return "";
        }
    }

    /** 当前 worklog 条数（用于软上限检查与 {@code /memory show}）。 */
    public int worklogSize(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        Path p = shortTermPath(sessionId);
        if (!Files.exists(p)) return 0;
        try {
            MemoryFile f = mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), MemoryFile.class);
            return f == null || f.messages == null ? 0 : f.messages.size();
        } catch (Exception ex) {
            return 0;
        }
    }

    /** 读 worklog 全部记录（包含 {@code [meta]}），供 {@code /memory show} 与崩溃恢复使用。 */
    public List<Record> readWorklog(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) sessionId = "default";
        Path p = shortTermPath(sessionId);
        if (!Files.exists(p)) return List.of();
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return List.of();
            MemoryFile f = mapper.readValue(raw, MemoryFile.class);
            return f == null || f.messages == null ? new ArrayList<>() : new ArrayList<>(f.messages);
        } catch (Exception ex) {
            log.warn("readWorklog failed for {}: {}", p, ex.getMessage());
            return new ArrayList<>();
        }
    }

    /** worklog 软上限检查 —— 超限时 warn（不截断，审计完整性优先）。 */
    public void enforceWorklogSoftCap(String sessionId) {
        int size = worklogSize(sessionId);
        if (size > WORKLOG_SOFT_CAP) {
            log.warn("worklog soft cap exceeded: session={} size={} > cap={} (no auto-trim)",
                    sessionId, size, WORKLOG_SOFT_CAP);
        }
    }

    /**
     * 估算已用 token（与 {@code ContextBudgetPolicy.estimateTextTokens} 同口径）。
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

    /** 估算消息列表的 token 数（与 ContextBudgetPolicy.estimateTextTokens 同口径）。 */
    public static long estimateMessagesTokens(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0L;
        long total = 0L;
        for (Message m : messages) {
            total += org.example.agent.context.budget.ContextBudgetPolicy.estimateTextTokens(extractText(m));
        }
        return total;
    }

    // ============ 持久化：路径 + JSON ============

    /** 当前 session 主对话流落盘路径。 */
    public Path shortTermPath(String sessionId) {
        return sessionsRoot.resolve(safeSessionId(sessionId)).resolve("short-term.json");
    }

    /** 当前 session 压缩后窗口落盘路径。 */
    public Path midTermPath(String sessionId) {
        return sessionsRoot.resolve(safeSessionId(sessionId)).resolve("mid-term.json");
    }

    /** 旧接口保留 —— 等价于 {@link #shortTermPath(String)}。 */
    public Path pathFor(String sessionId) {
        return shortTermPath(sessionId);
    }

    /** 外置工具结果路径。 */
    public Path toolResultPath(String sessionId, String toolCallId) {
        return sessionsRoot.resolve(safeSessionId(sessionId))
                .resolve("tool-results").resolve(safeFileName(toolCallId) + ".json");
    }

    public Path sessionsRoot() { return sessionsRoot; }
    public int toolResultInlineLimit() { return toolResultInlineLimit; }
    public int keepRecentRounds() { return keepRecentRounds; }

    // ============ 启动期加载 ============

    private void loadOnCreate(Session session) {
        // 1. worklog 总在内存中按需追加,不在 Session 中保留(只读磁盘)
        // 2. mid-term 优先;若缺失/陈旧 → 从 worklog 重建
        List<Message> restored = loadMidTermOrEmpty(session.getSessionId());
        if (restored.isEmpty()) {
            // 启动恢复路径:从 worklog 取最近 N 轮
            List<Record> worklog = readWorklog(session.getSessionId());
            if (worklog.isEmpty()) {
                log.debug("loadOnCreate: session={} no worklog and no mid-term; starting empty",
                        session.getSessionId());
                return;
            }
            List<Record> filtered = new ArrayList<>();
            for (Record r : worklog) {
                if (r == null || ROLE_META.equals(r.role)) continue;
                filtered.add(r);
            }
            List<Record> recent = tailRecentRounds(filtered, keepRecentRounds);
            List<Message> messages = new ArrayList<>();
            for (Record r : recent) {
                Message m = rebuildMessage(r);
                if (m != null) messages.add(m);
            }
            long before = estimateMessagesTokens(messages);
            if (before > 0L) {
                // 直接落盘 mid-term(startup-recover 元信息只入 mid-term,不入 worklog)
                persistMidTerm(session.getSessionId(), messages,
                        CompressionInfo.startupRecover(before, before, recent.size(),
                                "mid-term.json missing; rebuilt from worklog"));
                log.info("loadOnCreate: session={} mid-term.json missing; rebuilt {} records from worklog ({} rounds, ~{} tokens)",
                        session.getSessionId(), messages.size(), recent.size(), before);
            }
            restored = messages;
        }
        session.replaceAll(restored);
    }

    /**
     * 取 worklog 最近 N 轮 user-anchored 切片。轮数不足时返回全部。
     * 通过遍历 records 倒序数 user 数量达到 N 为止。
     */
    List<Record> tailRecentRounds(List<Record> records, int k) {
        if (records == null || records.isEmpty() || k <= 0) return new ArrayList<>();
        int targetUserStart = -1;
        int userCount = 0;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (ROLE_USER.equals(records.get(i).role)) {
                userCount++;
                if (userCount == k) {
                    targetUserStart = i;
                    break;
                }
            }
        }
        if (targetUserStart < 0) {
            return new ArrayList<>(records);
        }
        return new ArrayList<>(records.subList(targetUserStart, records.size()));
    }

    private boolean isStaleRelativeToWorklog(MemoryFile midTerm, String sessionId) {
        Instant midTermUpdatedAt = parseInstant(midTerm == null ? null : midTerm.updatedAt);
        if (midTermUpdatedAt == null) return true;
        Path wp = shortTermPath(sessionId);
        if (!Files.exists(wp)) return false;
        Instant latestMeta = lastCompressMetaTimestamp(wp);
        return latestMeta != null && latestMeta.isAfter(midTermUpdatedAt);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (Exception ignore) { return null; }
    }

    /**
     * 倒序扫描 worklog 找最近一条 {@code [meta]} 记录的 timestamp。
     * 轻量实现 —— worklog 软上限 10000 条，单次扫描可接受。
     */
    private Instant lastCompressMetaTimestamp(Path worklogPath) {
        if (!Files.exists(worklogPath)) return null;
        try {
            String raw = Files.readString(worklogPath, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return null;
            MemoryFile f = mapper.readValue(raw, MemoryFile.class);
            if (f == null || f.messages == null) return null;
            for (int i = f.messages.size() - 1; i >= 0; i--) {
                Record r = f.messages.get(i);
                if (r != null && ROLE_META.equals(r.role)) {
                    return parseInstant(r.timestamp);
                }
            }
            return null;
        } catch (Exception ex) {
            log.debug("lastCompressMetaTimestamp failed: {}", ex.getMessage());
            return null;
        }
    }

    // ============ 内部：worklog 追加 ============

    private void appendToWorklog(String sessionId, String role, String content,
                                 String toolCallId, List<ToolCallRecord> toolCalls, String toolResultRef) {
        Path p = shortTermPath(sessionId);
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
            MemoryFile f = new MemoryFile();
            f.schema = SCHEMA_VERSION;
            f.kind = KIND_SHORT_TERM;
            f.sessionId = sessionId;
            f.createdAt = r.timestamp;
            List<Record> list = new ArrayList<>();
            list.add(r);
            f.messages = list;
            atomicWriteJson(p, f);
        } else {
            try {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                MemoryFile existing = mapper.readValue(raw, MemoryFile.class);
                if (existing.messages == null) existing.messages = new ArrayList<>();
                existing.messages.add(r);
                existing.updatedAt = r.timestamp;
                atomicWriteJson(p, existing);
            } catch (Exception ex) {
                log.warn("appendToWorklog failed for {}: {}", p, ex.getMessage());
            }
        }
    }

    /**
     * 整文件原子写：临时文件 + rename。
     * rename 优先 ATOMIC_MOVE，文件系统不支持时回退到普通 rename。
     */
    void atomicWriteJson(Path target, MemoryFile data) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
                try {
                    Files.move(tmp, target,
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException atomicEx) {
                    Files.move(tmp, target,
                            LinkOption.NOFOLLOW_LINKS,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            log.warn("atomicWriteJson failed for {}: {}", target, ex.getMessage());
        }
    }

    // ============ 工具结果外置（保留旧 API） ============

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

    // ============ 内部 helper ============

    static Record metaRecord(CompressionInfo info) {
        Record r = new Record();
        r.role = ROLE_META;
        r.timestamp = Instant.now().toString();
        r.content = info.toLine();
        return r;
    }

    static Record recordFromMessage(Message m) {
        Record r = new Record();
        r.timestamp = Instant.now().toString();
        if (m instanceof UserMessage) {
            r.role = ROLE_USER;
            r.content = ((UserMessage) m).getText();
        } else if (m instanceof AssistantMessage asst) {
            r.role = ROLE_ASSISTANT;
            r.content = asst.getText();
        } else if (m instanceof SystemMessage sys) {
            r.role = ROLE_SYSTEM;
            r.content = sys.getText();
        } else {
            r.role = m.getClass().getSimpleName();
            r.content = extractText(m);
        }
        return r;
    }

    static Message rebuildMessage(Record r) {
        if (r == null || r.role == null) return null;
        return switch (r.role) {
            case ROLE_USER -> new UserMessage(r.content == null ? "" : r.content);
            case ROLE_ASSISTANT -> new AssistantMessage(r.content == null ? "" : r.content);
            case ROLE_SYSTEM -> new SystemMessage(r.content == null ? "" : r.content);
            default -> null;   // meta / tool / 未知角色:不重建为 Message
        };
    }

    static String renderMidTermAsText(MemoryFile f) {
        if (f == null || f.messages == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean metaEmitted = false;
        for (Record r : f.messages) {
            if (r == null) continue;
            if (ROLE_META.equals(r.role)) {
                if (!metaEmitted) {
                    sb.append("[meta] ").append(r.content == null ? "" : r.content).append("\n\n");
                    metaEmitted = true;
                }
                continue;
            }
            sb.append("[").append(r.role).append("]\n");
            sb.append(r.content == null ? "" : r.content).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String safeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "default";
        return sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "anon";
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (IOException ex) { return -1L; }
    }

    // ============ JSON DTO ============

    /**
     * 通用文件 envelope —— short-term.json / mid-term.json 共用结构。
     * 通过 {@link #kind} 字段区分用途。
     */
    public static final class MemoryFile {
        public String schema = SCHEMA_VERSION;
        public String kind;            // short-term | mid-term
        public String sessionId;
        public String createdAt;
        public String updatedAt;
        public List<Record> messages;
    }

    public static final class Record {
        public String role;            // user | assistant | system | tool | meta
        public String content;
        public String timestamp;
        public String messageId;       // 生成时分配,用于跨 record 关联
        public List<ToolCallRecord> toolCalls;     // assistant 专用
        public String toolCallId;      // tool 专用,对应 assistant.tool_calls[].id
        public String toolResultRef;   // tool 专用,外置引用(超过 inline 阈值时使用)
        public Integer size;           // 原始 content 字节数(外置时填)
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

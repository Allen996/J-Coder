package org.example.agent.context.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中期记忆存储（part3.md §6.3 / part4.md §7.2 / §7.4）。
 *
 * <p>每个 session 一份独立 JSON 文件 {@code .agent/sessions/{sessionId}/mid-term.json}。
 * 头部可评分元数据（{@code summary / topics / keywords / importance / pinned}）
 * 与四字段内容（{@code crossSessionProgress / sessionSummary / userFocus / contextualRules}）合并存盘。
 *
 * <p>更新时机：每轮对话结束后增量更新；会话结束时整体重生成。
 * 消费：当前 session 的 mid-term 始终注入；其他 session 的 mid-term 走 §7.7 评分流程。
 */
@Component
public class MidTermStore {

    private static final Logger log = LoggerFactory.getLogger(MidTermStore.class);

    public static final String SCHEMA_VERSION = "2";

    /**
     * 中期记忆内容（part4 §7.2 四字段结构 + §7.4 可评分元数据）。
     */
    @Getter
    public static final class MidTerm {
        private final String sessionId;
        private final List<String> crossSessionDone;
        private final List<String> crossSessionInProgress;
        private final List<String> crossSessionBlocked;
        private final String sessionSummary;
        private final List<String> userFocus;
        private final List<String> contextualRules;
        private final String summary;
        private final List<String> topics;
        private final List<String> keywords;
        private final int importance;
        private final Instant updatedAt;

        public MidTerm(String sessionId,
                       List<String> crossSessionDone,
                       List<String> crossSessionInProgress,
                       List<String> crossSessionBlocked,
                       String sessionSummary,
                       List<String> userFocus,
                       List<String> contextualRules,
                       String summary,
                       List<String> topics,
                       List<String> keywords,
                       int importance,
                       Instant updatedAt) {
            this.sessionId = sessionId;
            this.crossSessionDone = crossSessionDone == null ? new ArrayList<>() : crossSessionDone;
            this.crossSessionInProgress = crossSessionInProgress == null ? new ArrayList<>() : crossSessionInProgress;
            this.crossSessionBlocked = crossSessionBlocked == null ? new ArrayList<>() : crossSessionBlocked;
            this.sessionSummary = sessionSummary == null ? "" : sessionSummary;
            this.userFocus = userFocus == null ? new ArrayList<>() : userFocus;
            this.contextualRules = contextualRules == null ? new ArrayList<>() : contextualRules;
            this.summary = summary == null ? "" : summary;
            this.topics = topics == null ? new ArrayList<>() : topics;
            this.keywords = keywords == null ? new ArrayList<>() : keywords;
            this.importance = Math.max(1, Math.min(5, importance));
            this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        }

        /** 跨会话进度三态汇总，便于文本渲染。 */
        public List<String> crossSessionProgressAsList() {
            List<String> out = new ArrayList<>();
            if (!crossSessionDone.isEmpty()) {
                out.add("done: " + String.join("; ", crossSessionDone));
            }
            if (!crossSessionInProgress.isEmpty()) {
                out.add("in-progress: " + String.join("; ", crossSessionInProgress));
            }
            if (!crossSessionBlocked.isEmpty()) {
                out.add("blocked: " + String.join("; ", crossSessionBlocked));
            }
            return out;
        }

        /** Markdown 形态 —— 仍可用，便于旧路径渲染。 */
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("# crossSessionProgress\n");
            if (!crossSessionDone.isEmpty()) sb.append("- done: ").append(String.join("; ", crossSessionDone)).append("\n");
            if (!crossSessionInProgress.isEmpty()) sb.append("- inProgress: ").append(String.join("; ", crossSessionInProgress)).append("\n");
            if (!crossSessionBlocked.isEmpty()) sb.append("- blocked: ").append(String.join("; ", crossSessionBlocked)).append("\n");
            sb.append("\n# sessionSummary\n").append(sessionSummary).append("\n\n");
            sb.append("# userFocus\n");
            for (String u : userFocus) sb.append("- ").append(u).append("\n");
            sb.append("\n# contextualRules\n");
            for (String r : contextualRules) sb.append("- ").append(r).append("\n");
            return sb.toString();
        }

        /** 紧凑渲染，用于 ContextEntry 注入。 */
        public String toCompactText() {
            StringBuilder sb = new StringBuilder();
            if (!crossSessionDone.isEmpty()) sb.append("已完成: ").append(String.join("; ", crossSessionDone)).append("\n");
            if (!crossSessionInProgress.isEmpty()) sb.append("进行中: ").append(String.join("; ", crossSessionInProgress)).append("\n");
            if (!crossSessionBlocked.isEmpty()) sb.append("阻塞: ").append(String.join("; ", crossSessionBlocked)).append("\n");
            if (!sessionSummary.isEmpty()) sb.append("会话摘要: ").append(sessionSummary).append("\n");
            if (!userFocus.isEmpty()) sb.append("用户焦点: ").append(String.join("; ", userFocus)).append("\n");
            if (!contextualRules.isEmpty()) sb.append("本期规则: ").append(String.join("; ", contextualRules)).append("\n");
            return sb.toString().strip();
        }

        /** 空 mid-term。 */
        public static MidTerm empty(String sessionId) {
            return new MidTerm(sessionId,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    "", new ArrayList<>(), new ArrayList<>(),
                    "", new ArrayList<>(), new ArrayList<>(),
                    3, Instant.now());
        }
    }

    private final Map<String, MidTerm> cache = new ConcurrentHashMap<>();
    private final Path sessionsRoot;
    private final ObjectMapper mapper;

    public MidTermStore() {
        this(Paths.get(".agent", "sessions"));
    }

    public MidTermStore(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** sessionId 决定 .agent/sessions/{sessionId}/mid-term.json 路径。 */
    public Path pathFor(String sessionId) {
        return sessionsRoot.resolve(safeSessionId(sessionId)).resolve("mid-term.json");
    }

    /** 加载指定 session 的中期记忆；缺失或解析失败时返回 null。 */
    public MidTerm loadOrEmpty(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        MidTerm cached = cache.get(sessionId);
        if (cached != null) return cached;
        Path p = pathFor(sessionId);
        if (!Files.exists(p)) return null;
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) return null;
            MidTermFile data = mapper.readValue(raw, MidTermFile.class);
            if (data == null) return null;
            MidTerm parsed = toMidTerm(data);
            cache.put(sessionId, parsed);
            return parsed;
        } catch (Exception ex) {
            log.warn("MidTermStore load failed for {}: {}", p, ex.getMessage());
            return null;
        }
    }

    /** 更新并落盘。 */
    public void updateMidTerm(String sessionId, MidTerm midTerm) {
        if (sessionId == null || midTerm == null) return;
        cache.put(sessionId, midTerm);
        MidTermFile f = fromMidTerm(midTerm);
        f.schema = SCHEMA_VERSION;
        f.updatedAt = Instant.now().toString();
        Path p = pathFor(sessionId);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = Files.createTempFile(p.getParent(), "mid-term.", ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), f);
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
        } catch (IOException ex) {
            log.warn("MidTermStore persist failed for {}: {}", p, ex.getMessage());
        }
    }

    static MidTerm toMidTerm(MidTermFile f) {
        if (f == null) return null;
        CrossSessionProgress csp = f.crossSessionProgress == null
                ? new CrossSessionProgress() : f.crossSessionProgress;
        return new MidTerm(
                f.sessionId,
                csp.done == null ? new ArrayList<>() : csp.done,
                csp.inProgress == null ? new ArrayList<>() : csp.inProgress,
                csp.blocked == null ? new ArrayList<>() : csp.blocked,
                f.sessionSummary == null ? "" : f.sessionSummary,
                f.userFocus == null ? new ArrayList<>() : f.userFocus,
                f.contextualRules == null ? new ArrayList<>() : f.contextualRules,
                f.summary == null ? "" : f.summary,
                f.topics == null ? new ArrayList<>() : f.topics,
                f.keywords == null ? new ArrayList<>() : f.keywords,
                f.importance == 0 ? 3 : f.importance,
                f.updatedAt == null ? Instant.now() : Instant.parse(f.updatedAt)
        );
    }

    static MidTermFile fromMidTerm(MidTerm m) {
        MidTermFile f = new MidTermFile();
        f.sessionId = m.getSessionId();
        f.sessionSummary = m.getSessionSummary();
        f.userFocus = new ArrayList<>(m.getUserFocus());
        f.contextualRules = new ArrayList<>(m.getContextualRules());
        f.summary = m.getSummary();
        f.topics = new ArrayList<>(m.getTopics());
        f.keywords = new ArrayList<>(m.getKeywords());
        f.importance = m.getImportance();
        CrossSessionProgress csp = new CrossSessionProgress();
        csp.done = new ArrayList<>(m.getCrossSessionDone());
        csp.inProgress = new ArrayList<>(m.getCrossSessionInProgress());
        csp.blocked = new ArrayList<>(m.getCrossSessionBlocked());
        f.crossSessionProgress = csp;
        f.updatedAt = m.getUpdatedAt().toString();
        return f;
    }

    private static String safeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "default";
        return sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ============ JSON DTO ============

    public static final class MidTermFile {
        public String schema = SCHEMA_VERSION;
        public String sessionId;
        public String createdAt;
        public String updatedAt;
        public String summary;
        public List<String> topics;
        public List<String> keywords;
        public int importance;
        public CrossSessionProgress crossSessionProgress;
        public String sessionSummary;
        public List<String> userFocus;
        public List<String> contextualRules;
    }

    public static final class CrossSessionProgress {
        public List<String> done;
        public List<String> inProgress;
        public List<String> blocked;
    }
}
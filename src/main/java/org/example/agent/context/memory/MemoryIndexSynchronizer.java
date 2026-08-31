package org.example.agent.context.memory;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆文件创建时同步 {@link MemoryIndex}（part4.md §7.2 "MEMORY.md 维护" / §7.4 索引定位调整）。
 *
 * <p>本类只负责索引目录页同步，<b>不</b>承担匹配逻辑。
 * 实际评分由 {@link MemoryRecallScorer} 读取各记忆文件自身的 frontmatter / JSON 头部完成。
 *
 * <p>新版文件路径（part4 §7.8）：
 * <ul>
 *   <li>{@code .agent/sessions/{id}/short-term.json} → "session {id} 短期对话流 + worklog"</li>
 *   <li>{@code .agent/sessions/{id}/mid-term.json}   → "session {id} 中期窗口快照"</li>
 *   <li>{@code {projectRoot}/NNN-<topic>.md}        → "topic 摘要"（长期记忆按主题拆分）</li>
 *   <li>{@code .agent/sessions/{id}/plan.json + dag-state.json}（阶段 2 起,不再走 MEMORY.md 索引）</li>
 * </ul>
 *
 * <p>阶段 2 起,plan 不再写 MEMORY.md 索引 —— DagStateRepository 按 sessionId 自动定位。
 */
@Slf4j
@Component
public class MemoryIndexSynchronizer {

    private final MemoryIndex memoryIndex;
    private final LongTermStore longTermStore;
    private final SessionMessageStore sessionStore;
    private final String projectRoot;

    public MemoryIndexSynchronizer(MemoryIndex memoryIndex,
                                   LongTermStore longTermStore,
                                   SessionMessageStore sessionStore) {
        this.memoryIndex = memoryIndex;
        this.longTermStore = longTermStore;
        this.sessionStore = sessionStore;
        this.projectRoot = "";
    }

    @PostConstruct
    public void syncKnownPaths() {
        try {
            noteLongTermTopics();
            // 扫描磁盘上已有的 mid-term / short-term
            Path sessionsRoot;
            if (projectRoot.isBlank()) {
                if (sessionStore != null) {
                    sessionsRoot = sessionStore.sessionsRoot();
                } else {
                    sessionsRoot = null;
                }
            } else {
                sessionsRoot = java.nio.file.Paths.get(projectRoot, ".agent", "sessions");
            }
            if (sessionsRoot != null && Files.exists(sessionsRoot)) {
                try (var stream = Files.list(sessionsRoot)) {
                    stream.filter(Files::isDirectory).forEach(dir -> {
                        String sessionId = dir.getFileName().toString();
                        Path mt = dir.resolve("mid-term.json");
                        Path st = dir.resolve("short-term.json");
                        Path mtOld = dir.resolve("mid-term.md");
                        Path stOld = dir.resolve("short-term.md");
                        if (Files.exists(mt) || Files.exists(mtOld)) noteMidTermSession(sessionId);
                        if (Files.exists(st) || Files.exists(stOld)) noteShortTermSession(sessionId);
                    });
                }
            }
        } catch (Exception ex) {
            log.warn("MemoryIndexSynchronizer.syncKnownPaths failed: {}", ex.getMessage());
        }
    }

    public void noteShortTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "short-term.json");
        memoryIndex.add(rel, "session " + sessionId + " 短期对话流 + worklog");
    }

    public void noteMidTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "mid-term.json");
        memoryIndex.add(rel, "session " + sessionId + " 中期窗口快照");
    }

    /** 扫描项目根目录下的所有 NNN-*.md 长期记忆文件并加入索引。 */
    public void noteLongTermTopics() {
        if (longTermStore == null) return;
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            String filename = t.filename();
            memoryIndex.add(filename, t.getSummary().isEmpty() ? "长期记忆 " + t.getSlug() : t.getSummary());
        }
    }

    private String relativeSessionPath(String sessionId, String fileName) {
        return ".agent/sessions/" + sessionId + "/" + fileName;
    }

    // ============ 给 LongTermMaintainer 用的快照接口（part4 §7.6 提示词 existingTopics/existingTitles）=============

    public java.util.List<String> snapshotLongTermTopicSlugs() {
        if (longTermStore == null) return java.util.List.of();
        java.util.List<String> slugs = new java.util.ArrayList<>();
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            slugs.add(t.getSeq() + "-" + t.getSlug());
        }
        return slugs;
    }

    public java.util.List<String> snapshotLongTermEntryTitles() {
        if (longTermStore == null) return java.util.List.of();
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            for (LongTermStore.Entry e : t.getEntries()) {
                if (!e.getContent().isBlank()) titles.add(e.getContent());
            }
        }
        return titles;
    }
}
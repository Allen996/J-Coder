package org.example.agent.context.memory;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆文件创建时同步 {@link MemoryIndex}（part4.md §7.2 "MEMORY.md 维护"）。
 *
 * <p>三类记忆文件对应不同的索引行：
 * <ul>
 *   <li>{@code .agent/sessions/{id}/short-term.md} → "session {id} 短期对话流"</li>
 *   <li>{@code .agent/sessions/{id}/mid-term.md}   → "session {id} 中期摘要"</li>
 *   <li>{@code Nico.md}                              → "项目骨架"</li>
 * </ul>
 *
 * <p>使用方式：写入方（{@link LongTermMaintainer} / 每轮 mid-term 钩子）调用本类的
 * {@link #noteShortTermSession(String)} 等方法；本类负责幂等地写入 MEMORY.md。
 *
 * <p>启动时执行 {@link #syncKnownPaths()}，把磁盘上已存在的文件补登记一遍，
 * 保证冷启动后 MEMORY.md 不会丢条目（part4 §7.5）。
 */
@Slf4j
@Component
public class MemoryIndexSynchronizer {

    private final MemoryIndex memoryIndex;
    private final LongTermStore longTermStore;
    private final MidTermStore midTermStore;
    private final SessionMessageStore sessionStore;
    private final String projectRoot;

    public MemoryIndexSynchronizer(MemoryIndex memoryIndex,
                                   LongTermStore longTermStore,
                                   MidTermStore midTermStore,
                                   SessionMessageStore sessionStore) {
        this.memoryIndex = memoryIndex;
        this.longTermStore = longTermStore;
        this.midTermStore = midTermStore;
        this.sessionStore = sessionStore;
        this.projectRoot = "";
    }

    @PostConstruct
    public void syncKnownPaths() {
        try {
            noteLongTermFile();
            // 扫描磁盘上已有的 mid-term / short-term
            Path sessionsRoot;
            if (projectRoot.isBlank()) {
                Path probe = midTermStore.pathFor("__probe__");
                sessionsRoot = probe == null ? null : probe.getParent().getParent();
            } else {
                sessionsRoot = java.nio.file.Paths.get(projectRoot, ".agent", "sessions");
            }
            if (sessionsRoot == null) return;
            if (!Files.exists(sessionsRoot)) return;
            try (var stream = Files.list(sessionsRoot)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    String sessionId = dir.getFileName().toString();
                    Path mt = dir.resolve("mid-term.md");
                    Path st = dir.resolve("short-term.md");
                    if (Files.exists(mt)) noteMidTermSession(sessionId);
                    if (Files.exists(st)) noteShortTermSession(sessionId);
                });
            }
        } catch (Exception ex) {
            log.warn("MemoryIndexSynchronizer.syncKnownPaths failed: {}", ex.getMessage());
        }
    }

    public void noteShortTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "short-term.md");
        memoryIndex.add(rel, "session " + sessionId + " 短期对话流");
    }

    public void noteMidTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "mid-term.md");
        memoryIndex.add(rel, "session " + sessionId + " 中期摘要");
    }

    public void noteLongTermFile() {
        memoryIndex.add("Nico.md", "项目骨架");
    }

    private String relativeSessionPath(String sessionId, String fileName) {
        return ".agent/sessions/" + sessionId + "/" + fileName;
    }
}

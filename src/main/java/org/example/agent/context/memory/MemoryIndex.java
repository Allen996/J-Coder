package org.example.agent.context.memory;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 记忆索引目录页（part4.md §7.2 / §7.4 索引定位调整）。
 *
 * <p>本类只承担 {@code MEMORY.md} 的目录页读写 —— <b>不再</b>承载匹配逻辑。
 * 评分召回由 {@link MemoryRecallScorer} 接管，所需结构化信号从各记忆文件自身的 frontmatter / JSON 头部读取。
 *
 * <p>收录策略（§7.2 取消 LRU）：索引收录全部记忆文件，不再限制 20 条；池子变大不会导致注入变多，
 * 只让评分计算量线性增长（条目数达千级前不构成问题）。
 */
@Component
public class MemoryIndex {

    private static final Logger log = LoggerFactory.getLogger(MemoryIndex.class);

    @Getter
    public static final class IndexEntry {
        private final String path;
        private final String summary;
        private final Instant addedAt;

        public IndexEntry(String path, String summary, Instant addedAt) {
            this.path = path;
            this.summary = summary == null ? "" : summary;
            this.addedAt = addedAt == null ? Instant.now() : addedAt;
        }

        public String toLine() {
            return path + " — " + summary;
        }
    }

    private final Path memoryPath;
    private final AtomicReference<List<IndexEntry>> cache = new AtomicReference<>(new ArrayList<>());

    public MemoryIndex() {
        this(Paths.get("MEMORY.md"));
    }

    public MemoryIndex(@Value("${agent.project-root:}") String projectRoot) {
        this(projectRoot == null || projectRoot.isBlank() ? Paths.get("MEMORY.md") : Paths.get(projectRoot, "MEMORY.md"));
    }

    public MemoryIndex(Path memoryPath) {
        this.memoryPath = memoryPath;
        reload();
    }

    /** 加载或返回空。 */
    public List<IndexEntry> loadOrEmpty() {
        return cache.get();
    }

    public void reload() {
        if (!Files.exists(memoryPath)) {
            cache.set(new ArrayList<>());
            return;
        }
        try {
            String raw = Files.readString(memoryPath, java.nio.charset.StandardCharsets.UTF_8);
            cache.set(parseBody(raw));
        } catch (Exception ex) {
            log.warn("MemoryIndex reload failed for {}: {}", memoryPath, ex.getMessage());
            cache.set(new ArrayList<>());
        }
    }

    /** 添加一条索引（去重，<b>不</b>做 LRU 淘汰）。 */
    public void add(String path, String summary) {
        if (path == null || path.isBlank()) return;
        List<IndexEntry> next = new ArrayList<>(cache.get());
        next.removeIf(e -> e.path.equals(path));
        next.add(0, new IndexEntry(path, summary, Instant.now()));
        cache.set(next);
        persist();
    }

    /** 移除一条索引（不删除文件）。 */
    public void remove(String path) {
        if (path == null) return;
        List<IndexEntry> next = new ArrayList<>(cache.get());
        boolean changed = next.removeIf(e -> path.equals(e.path));
        if (changed) {
            cache.set(next);
            persist();
        }
    }

    /** 整体重渲染（§7.4 MEMORY.md 由内存缓存整体写出，避免漂移）。 */
    public void persist() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("schema", "2");
        fm.put("updatedAt", Instant.now().toString());
        fm.put("entries", String.valueOf(cache.get().size()));
        StringBuilder body = new StringBuilder();
        body.append("# memory_index\n");
        body.append("# 人类可读的目录页。评分召回由 MemoryRecallScorer 直接读取各记忆文件 frontmatter / JSON 头部,不再解析本文件。\n\n");
        for (IndexEntry e : cache.get()) {
            body.append("- ").append(e.toLine()).append("\n");
        }
        try {
            Path parent = memoryPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path dir = parent != null ? parent : Paths.get(".");
            Path tmp = Files.createTempFile(dir, "MEMORY.", ".md.tmp");
            String full = Frontmatter.render(fm) + "\n" + body;
            Files.writeString(tmp, full, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, memoryPath,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, memoryPath,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.warn("MemoryIndex.persist failed: {}", ex.getMessage());
        }
    }

    /**
     * 兼容旧接口 —— 现在不做真实匹配，按入参返回前 N 条；由 {@link MemoryRecallScorer} 接管真实评分。
     *
     * @deprecated 使用 {@link MemoryRecallScorer#score(String, String)}
     */
    @Deprecated
    public List<IndexEntry> matchTopN(String query, int n) {
        List<IndexEntry> all = cache.get();
        if (all.isEmpty()) return all;
        if (query == null || query.isBlank()) {
            return all.subList(0, Math.min(n, all.size()));
        }
        // 退化版：仅保留子串命中（与 §7.7 之前的老行为兼容）
        List<IndexEntry> matched = new ArrayList<>();
        String lower = query.toLowerCase();
        for (IndexEntry e : all) {
            String text = (e.path + " " + e.summary).toLowerCase();
            if (text.contains(lower)) matched.add(e);
        }
        if (matched.isEmpty()) return all.subList(0, Math.min(n, all.size()));
        return matched;
    }

    /** 解析正文：移除 frontmatter，逐行读取 {@code - path — summary}。 */
    static List<IndexEntry> parseBody(String raw) {
        List<IndexEntry> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String body = raw;
        if (body.startsWith("---")) {
            int second = body.indexOf("\n---", 3);
            if (second > 0) {
                int bodyStart = body.indexOf('\n', second + 4);
                if (bodyStart > 0) body = body.substring(bodyStart + 1);
            }
        }
        for (String line : body.split("\n")) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (s.startsWith("- ")) s = s.substring(2);
            int sep = s.indexOf(" — ");
            if (sep < 0) sep = s.indexOf(" - ");
            if (sep < 0) {
                out.add(new IndexEntry(s, "", Instant.now()));
            } else {
                out.add(new IndexEntry(s.substring(0, sep), s.substring(sep + 3), Instant.now()));
            }
        }
        return out;
    }

    public Path path() { return memoryPath; }

    /**
     * 兼容旧测试接口 —— 不再做 LRU 淘汰，返回 {@link Integer#MAX_VALUE}。
     *
     * @deprecated LRU 已取消；保留接口仅为通过现有 sanity 测试。
     */
    @Deprecated
    public int lruCapacity() { return Integer.MAX_VALUE; }
}
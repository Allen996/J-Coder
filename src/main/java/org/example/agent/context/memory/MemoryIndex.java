package org.example.agent.context.memory;

import lombok.Getter;
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
 * 记忆索引（part3.md §6.3 / part4.md §7.2）。
 *
 * <p>项目根目录唯一 Markdown 文件 {@code MEMORY.md}（头部 YAML frontmatter）。
 * 每行格式：{@code <记忆文件路径> - <简介>}，如 {@code Nico.md - 项目骨架}、{@code .agent/sessions/2026-07-23-001/mid-term.md - 会话 001 摘要}。
 *
 * <p>LRU 策略：仅保留最近 20 个记忆条目；过期记忆文件不删除，仅从索引移除。
 * 加载：session 开始时加载；不存在时无需加载（视为空索引）。
 *
 * <p>消费：ContextBuilder 装配时隐式调用匹配算法选最相关 5 条；匹配失败降级关键词。
 */
@Component
public class MemoryIndex {

    public static final int DEFAULT_LRU_CAPACITY = 20;

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
            return path + " - " + summary;
        }
    }

    private final Path memoryPath;
    private final int lruCapacity;
    private final AtomicReference<List<IndexEntry>> cache = new AtomicReference<>(new ArrayList<>());

    public MemoryIndex() {
        this(Paths.get("MEMORY.md"), DEFAULT_LRU_CAPACITY);
    }

    public MemoryIndex(@Value("${agent.project-root:}") String projectRoot) {
        this(projectRoot == null || projectRoot.isBlank() ? Paths.get("MEMORY.md") : Paths.get(projectRoot, "MEMORY.md"));
    }

    public MemoryIndex(Path memoryPath) {
        this(memoryPath, DEFAULT_LRU_CAPACITY);
    }

    public MemoryIndex(Path memoryPath, int lruCapacity) {
        this.memoryPath = memoryPath;
        this.lruCapacity = lruCapacity;
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
        String raw = MemoryFile.readOrBackup(memoryPath);
        cache.set(parseBody(raw));
    }

    /** 添加一条索引（LRU 头插；超过容量从尾部移除）。 */
    public void add(String path, String summary) {
        if (path == null || path.isBlank()) return;
        List<IndexEntry> next = new ArrayList<>(cache.get());
        next.removeIf(e -> e.path.equals(path));
        next.add(0, new IndexEntry(path, summary, Instant.now()));
        while (next.size() > lruCapacity) {
            next.remove(next.size() - 1);
        }
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

    public void persist() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("updatedAt", Instant.now().toString());
        fm.put("capacity", String.valueOf(lruCapacity));
        StringBuilder body = new StringBuilder();
        body.append("# memory_index\n");
        body.append("# 下面是当前活跃的记忆条目（LRU ").append(lruCapacity).append("）。过期文件不删除，仅从索引移除。\n\n");
        for (IndexEntry e : cache.get()) {
            body.append("- ").append(e.toLine()).append("\n");
        }
        try {
            Path parent = memoryPath.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception ignore) { }
        MemoryFile.writeAtomic(memoryPath, fm, body.toString());
    }

    /** 简单关键词匹配（取前 5 条命中；匹配失败降级用最近 5 条）。 */
    public List<IndexEntry> matchTopN(String query, int n) {
        List<IndexEntry> all = cache.get();
        if (all.isEmpty()) return all;
        if (query == null || query.isBlank()) {
            return all.subList(0, Math.min(n, all.size()));
        }
        String[] keywords = query.toLowerCase().split("\\s+");
        List<IndexEntry> matched = new ArrayList<>();
        for (IndexEntry e : all) {
            String lower = (e.path + " " + e.summary).toLowerCase();
            for (String kw : keywords) {
                if (kw.isEmpty()) continue;
                if (lower.contains(kw)) { matched.add(e); break; }
            }
            if (matched.size() >= n) break;
        }
        if (matched.isEmpty()) {
            return all.subList(0, Math.min(n, all.size()));
        }
        return matched;
    }

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
            int sep = s.indexOf(" - ");
            if (sep < 0) {
                out.add(new IndexEntry(s, "", Instant.now()));
            } else {
                out.add(new IndexEntry(s.substring(0, sep), s.substring(sep + 3), Instant.now()));
            }
        }
        return out;
    }

    public Path path() { return memoryPath; }
    public int lruCapacity() { return lruCapacity; }
}

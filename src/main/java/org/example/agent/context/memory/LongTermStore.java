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
 * 长期记忆存储（part3.md §6.3 / part4.md §7.2）。
 *
 * <p>项目根目录下唯一一份 Markdown 文件 {@code Nico.md}。头部 YAML frontmatter 记录元数据，
 * 正文按分类（{@code # red_line} / {@code # coding_style} / {@code # decision} / {@code # dependency} / {@code # convention}）用 Markdown 列表组织。
 *
 * <p>更新策略：追加式更新；旧条目可标注 deprecated 但不删除；支持用户用编辑器直接维护。
 * 消费：session 开始时从 Nico.md 全量加载进 prompt（项目骨架小，常驻可见）。
 *
 * <p>v1 简化：单进程内 AtomicReference 缓存最新内容；解析失败时自动备份为 {@code Nico.md.corrupted-*}.
 */
@Component
public class LongTermStore {

    public enum Category {
        RED_LINE("red_line", "项目红线"),
        CODING_STYLE("coding_style", "编程风格约定"),
        DECISION("decision", "关键技术决策"),
        DEPENDENCY("dependency", "依赖与工具"),
        CONVENTION("convention", "重要约定");

        public final String wire;
        public final String display;

        Category(String wire, String display) {
            this.wire = wire;
            this.display = display;
        }
    }

    @Getter
    public static final class Entry {
        private final Category category;
        private final String content;
        private final int importance;
        private final Instant addedAt;

        public Entry(Category category, String content, int importance, Instant addedAt) {
            this.category = category;
            this.content = content == null ? "" : content;
            this.importance = importance;
            this.addedAt = addedAt == null ? Instant.now() : addedAt;
        }

        public String toYamlBlock() {
            return "- category: " + category.wire + "\n"
                    + "  content: \"" + content.replace("\"", "\\\"") + "\"\n"
                    + "  importance: " + importance + "\n"
                    + "  addedAt: " + addedAt.toString() + "\n";
        }
    }

    private final Path nicoPath;
    private final AtomicReference<List<Entry>> cache = new AtomicReference<>(new ArrayList<>());

    public LongTermStore() {
        this(Paths.get("Nico.md"));
    }

    public LongTermStore(@Value("${agent.project-root:}") String projectRoot) {
        this(projectRoot == null || projectRoot.isBlank() ? Paths.get("Nico.md") : Paths.get(projectRoot, "Nico.md"));
    }

    public LongTermStore(Path nicoPath) {
        this.nicoPath = nicoPath;
        reload();
    }

    /** 加载或返回空。 */
    public List<Entry> loadOrEmpty() {
        return cache.get();
    }

    /** 重新从磁盘读取。 */
    public void reload() {
        if (!Files.exists(nicoPath)) {
            cache.set(new ArrayList<>());
            return;
        }
        String raw = MemoryFile.readOrBackup(nicoPath);
        cache.set(parseBody(raw));
    }

    /** 追加一条候选条目（v1 简化：直接 append + 整体重写）。 */
    public void append(Entry entry) {
        if (entry == null) return;
        List<Entry> next = new ArrayList<>(cache.get());
        next.add(entry);
        cache.set(next);
        persist();
    }

    /** 渲染整文件并落盘。 */
    public void persist() {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("updatedAt", Instant.now().toString());
        String body = renderBody(cache.get());
        try {
            Files.createDirectories(nicoPath.getParent());
        } catch (Exception ignore) { }
        MemoryFile.writeAtomic(nicoPath, fm, body);
    }

    /** 全部正文渲染。 */
    String renderBody(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Category cat : Category.values()) {
            sb.append("# ").append(cat.display).append("\n");
            boolean any = false;
            for (Entry e : entries) {
                if (e.category == cat) {
                    sb.append("```yaml\n").append(e.toYamlBlock()).append("```\n");
                    any = true;
                }
            }
            if (!any) sb.append("_(empty)_\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 解析正文：按 # 标题分段，每段内 ```yaml 块解析。 */
    static List<Entry> parseBody(String raw) {
        List<Entry> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        String body = raw;
        if (body.startsWith("---")) {
            int second = body.indexOf("\n---", 3);
            if (second > 0) {
                int bodyStart = body.indexOf('\n', second + 4);
                if (bodyStart > 0) body = body.substring(bodyStart + 1);
            }
        }
        // 按 # 行分段
        String[] sections = body.split("(?m)^# ");
        for (String sec : sections) {
            if (sec.isBlank()) continue;
            String head = sec.lines().findFirst().orElse("").strip();
            Category cat = null;
            for (Category c : Category.values()) {
                if (c.display.equals(head)) { cat = c; break; }
            }
            if (cat == null) continue;
            // 提取 yaml 块
            StringBuilder yaml = new StringBuilder();
            boolean inBlock = false;
            for (String line : sec.split("\n")) {
                if (line.strip().startsWith("```yaml")) { inBlock = true; continue; }
                if (line.strip().startsWith("```")) { inBlock = false; yaml.append("---\n"); continue; }
                if (inBlock) yaml.append(line).append("\n");
            }
            if (yaml.length() == 0) continue;
            // 简化解析：每个 yaml 块包含若干 entries
            String[] docs = yaml.toString().split("(?m)^---\\s*$");
            for (String doc : docs) {
                if (doc.isBlank()) continue;
                Entry e = parseYamlEntry(doc, cat);
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    static Entry parseYamlEntry(String doc, Category fallbackCategory) {
        Category cat = fallbackCategory;
        String content = "";
        int importance = 3;
        Instant addedAt = Instant.now();
        for (String line : doc.split("\n")) {
            String s = line.strip();
            if (s.isEmpty()) continue;
            if (s.startsWith("- ")) s = s.substring(2);
            int colon = s.indexOf(':');
            if (colon < 0) continue;
            String k = s.substring(0, colon).strip();
            String v = s.substring(colon + 1).strip();
            if (v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1).replace("\\\"", "\"");
            }
            switch (k) {
                case "category" -> {
                    for (Category c : Category.values()) if (c.wire.equals(v)) cat = c;
                }
                case "content" -> content = v;
                case "importance" -> {
                    try { importance = Integer.parseInt(v); } catch (NumberFormatException ignore) { }
                }
                case "addedAt" -> {
                    try { addedAt = Instant.parse(v); } catch (Exception ignore) { }
                }
                default -> { }
            }
        }
        if (content.isEmpty()) return null;
        return new Entry(cat, content, importance, addedAt);
    }

    public Path path() { return nicoPath; }
}

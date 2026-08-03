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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 长期记忆存储（part3.md §6.3 / part4.md §7.2 / §7.4 多文件拆分版）。
 *
 * <p>项目根目录下多个 Markdown 文件，按主题拆分：
 * <pre>
 *   {projectRoot}/001-spring-config.md
 *   {projectRoot}/002-context-assembly.md
 *   ...
 *   {projectRoot}/MEMORY.md          ← 人类可读目录页（见 {@link MemoryIndex}）
 * </pre>
 *
 * <p>每个主题文件头部 YAML frontmatter（{@code seq / topic / summary / topics / keywords / importance / pinned / entryCount / createdAt / updatedAt}），
 * 正文按四类（{@code # user / # feedback / # project / # reference}）用 YAML 代码块组织条目。
 *
 * <p>更新策略：追加式更新；旧条目可标 {@code deprecated: true} 但不删除；用户可直接用编辑器维护（重命名主题摘要或在文件间迁移条目）。
 * 消费：{@code pinned=true} 或 {@code importance=5} 的条目常驻注入，其余条目以主题文件为单元走 §7.7 评分召回。
 */
@Component
public class LongTermStore {

    private static final Logger log = LoggerFactory.getLogger(LongTermStore.class);

    /** Part4 §7.2 长期记忆四分类。 */
    public enum Category {
        USER("user", "用户偏好"),
        FEEDBACK("feedback", "协作风格与禁忌"),
        PROJECT("project", "项目架构与决策"),
        REFERENCE("reference", "外部信息索引");

        public final String wire;
        public final String display;

        Category(String wire, String display) {
            this.wire = wire;
            this.display = display;
        }

        public static Category fromWire(String wire) {
            if (wire == null) return FEEDBACK;
            String w = wire.trim().toLowerCase();
            for (Category c : values()) {
                if (c.wire.equals(w)) return c;
            }
            return FEEDBACK;
        }
    }

    /** 长期记忆单条（part4 §7.4 entry YAML code block）。 */
    @Getter
    public static final class Entry {
        private final String id;
        private final Category category;
        private final String content;
        private final int importance;
        private final boolean pinned;
        private final String evidence;
        private final String reason;
        private final String topic;       // 所属主题 file slug
        private final Instant addedAt;
        private final boolean deprecated;

        public Entry(String id, Category category, String content, int importance,
                     boolean pinned, String evidence, String reason,
                     String topic, Instant addedAt, boolean deprecated) {
            this.id = id == null ? "" : id;
            this.category = category == null ? Category.FEEDBACK : category;
            this.content = content == null ? "" : content;
            this.importance = Math.max(1, Math.min(5, importance));
            this.pinned = pinned;
            this.evidence = evidence == null ? "" : evidence;
            this.reason = reason == null ? "" : reason;
            this.topic = topic == null ? "" : topic;
            this.addedAt = addedAt == null ? Instant.now() : addedAt;
            this.deprecated = deprecated;
        }

        /** 旧 4 字段兼容构造器（迁移期使用）。 */
        public Entry(Category category, String content, int importance, Instant addedAt) {
            this("", category, content, importance, false, "", "", "", addedAt, false);
        }

        public String toYamlBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("- id: ").append(id).append("\n");
            sb.append("  category: ").append(category.wire).append("\n");
            sb.append("  content: \"").append(content.replace("\"", "\\\"")).append("\"\n");
            sb.append("  importance: ").append(importance).append("\n");
            sb.append("  pinned: ").append(pinned).append("\n");
            if (!evidence.isEmpty()) sb.append("  evidence: \"").append(evidence.replace("\"", "\\\"")).append("\"\n");
            if (!reason.isEmpty()) sb.append("  reason: \"").append(reason.replace("\"", "\\\"")).append("\"\n");
            sb.append("  addedAt: ").append(addedAt.toString()).append("\n");
            if (deprecated) sb.append("  deprecated: true\n");
            return sb.toString();
        }
    }

    /** 主题文件元数据（part4 §7.4 frontmatter + 索引召回用）。 */
    @Getter
    public static final class Topic {
        private final String seq;        // "001"
        private final String slug;       // "spring-config"
        private final Path path;         // 绝对路径
        private final String summary;    // ≤ 40 字
        private final List<String> topics;
        private final List<String> keywords;
        private final int importance;
        private final boolean pinned;    // 主题文件是否常驻
        private final int entryCount;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final List<Entry> entries;

        public Topic(String seq, String slug, Path path, String summary,
                     List<String> topics, List<String> keywords, int importance, boolean pinned,
                     int entryCount, Instant createdAt, Instant updatedAt, List<Entry> entries) {
            this.seq = seq;
            this.slug = slug;
            this.path = path;
            this.summary = summary == null ? "" : summary;
            this.topics = topics == null ? new ArrayList<>() : topics;
            this.keywords = keywords == null ? new ArrayList<>() : keywords;
            this.importance = Math.max(1, Math.min(5, importance));
            this.pinned = pinned;
            this.entryCount = entryCount;
            this.createdAt = createdAt == null ? Instant.now() : createdAt;
            this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
            this.entries = entries == null ? new ArrayList<>() : entries;
        }

        public String filename() {
            return seq + "-" + slug + ".md";
        }

        public String toIndexLine() {
            return "- [topic] " + filename() + " — " + summary;
        }

        /** 渲染正文（按四分类）。 */
        public String renderBody() {
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

        public Map<String, Object> toFrontmatter() {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("schema", "2");
            fm.put("seq", seq);
            fm.put("topic", slug);
            fm.put("summary", summary);
            fm.put("topics", topics);
            fm.put("keywords", keywords);
            fm.put("importance", importance);
            fm.put("pinned", pinned);
            fm.put("entryCount", entries.size());
            fm.put("createdAt", createdAt.toString());
            fm.put("updatedAt", updatedAt.toString());
            return fm;
        }
    }

    private final Path rootDir;
    private final AtomicReference<List<Topic>> topicsCache = new AtomicReference<>(new ArrayList<>());
    private final Map<String, Topic> topicBySeqSlug = new ConcurrentHashMap<>();

    public LongTermStore() {
        this(Paths.get("."));
    }

    public LongTermStore(@Value("${agent.project-root:}") String projectRoot) {
        this(projectRoot == null || projectRoot.isBlank() ? Paths.get(".") : Paths.get(projectRoot));
    }

    public LongTermStore(Path rootDir) {
        this.rootDir = rootDir;
        reload();
    }

    /** 加载全部主题文件。 */
    public List<Topic> loadTopics() {
        return topicsCache.get();
    }

    /** 加载全部条目（扁平），跨主题聚合。 */
    public List<Entry> loadOrEmpty() {
        List<Entry> all = new ArrayList<>();
        for (Topic t : topicsCache.get()) all.addAll(t.entries);
        return all;
    }

    /** 重新从磁盘读取。 */
    public void reload() {
        List<Topic> next = new ArrayList<>();
        topicBySeqSlug.clear();
        if (!Files.exists(rootDir)) {
            topicsCache.set(next);
            return;
        }
        try (var stream = Files.list(rootDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.matches("\\d{3}-.+\\.md");
                    })
                    .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .forEach(p -> {
                        Topic t = readTopic(p);
                        if (t != null) {
                            next.add(t);
                            topicBySeqSlug.put(t.seq + "-" + t.slug, t);
                        }
                    });
        } catch (Exception ex) {
            log.warn("LongTermStore.reload failed: {}", ex.getMessage());
        }
        topicsCache.set(next);
    }

    /** 分配下一个可用序号（从 001 起，跳过已占用）。 */
    public String nextSeq() {
        int max = 0;
        for (Topic t : topicsCache.get()) {
            try { max = Math.max(max, Integer.parseInt(t.seq)); } catch (NumberFormatException ignore) { }
        }
        return String.format("%03d", max + 1);
    }

    /**
     * 追加一条 entry。
     * <p>若 entry.topic 与已有主题匹配 → 追加到对应文件；
     * 否则创建新主题文件（slug 取自 entry.topic 字段；为空时用 {@code general}）。
     */
    public Topic append(Entry entry) {
        if (entry == null || entry.content.isBlank()) return null;
        Topic target;
        if (entry.topic != null && !entry.topic.isBlank()) {
            target = findTopicBySlugOrSeq(entry.topic);
            if (target == null) {
                target = createTopic(entry.topic, "", new ArrayList<>(), new ArrayList<>(), 3, false);
            }
        } else {
            target = findTopicBySlugOrSeq("general");
            if (target == null) {
                target = createTopic("general", "通用", new ArrayList<>(), new ArrayList<>(), 3, false);
            }
        }
        return appendToTopic(target, entry);
    }

    /** 按 slug 或 seq-slug 查找主题。 */
    public Topic findTopicBySlugOrSeq(String slugOrSeq) {
        if (slugOrSeq == null || slugOrSeq.isBlank()) return null;
        for (Topic t : topicsCache.get()) {
            if (t.slug.equals(slugOrSeq) || (t.seq + "-" + t.slug).equals(slugOrSeq)) {
                return t;
            }
        }
        return null;
    }

    /** 显式创建一个新主题（topic-naming profile 由调用方完成）。 */
    public Topic createTopic(String slug, String summary,
                             List<String> topics, List<String> keywords,
                             int importance, boolean pinned) {
        if (slug == null || slug.isBlank()) slug = "general";
        String seq = nextSeq();
        Topic t = new Topic(seq, slug, rootDir.resolve(seq + "-" + slug + ".md"),
                summary, topics, keywords, importance, pinned, 0,
                Instant.now(), Instant.now(), new ArrayList<>());
        writeTopic(t);
        // 缓存更新
        List<Topic> next = new ArrayList<>(topicsCache.get());
        next.add(t);
        topicsCache.set(next);
        topicBySeqSlug.put(t.seq + "-" + t.slug, t);
        return t;
    }

    /** 把 entry 追加到指定主题文件。 */
    public Topic appendToTopic(Topic topic, Entry entry) {
        if (topic == null || entry == null) return null;
        // 用 seq-slug 找最新缓存版本，避免使用陈旧引用导致数据丢失
        Topic current = findTopicBySlugOrSeq(topic.seq + "-" + topic.slug);
        Topic base = current != null ? current : topic;
        Topic working = new Topic(base.seq, base.slug, base.path,
                base.summary, base.topics, base.keywords, base.importance, base.pinned,
                base.entryCount, base.createdAt, Instant.now(),
                appendEntry(base.entries, entry));
        writeTopic(working);
        replaceTopic(working);
        return working;
    }

    private static List<Entry> appendEntry(List<Entry> existing, Entry incoming) {
        List<Entry> next = new ArrayList<>(existing);
        String id = incoming.id.isBlank()
                ? topicPrefix(incoming.topic) + "-" + incoming.category.wire + "-" + System.currentTimeMillis()
                : incoming.id;
        next.add(new Entry(id, incoming.category, incoming.content, incoming.importance,
                incoming.pinned, incoming.evidence, incoming.reason, incoming.topic,
                incoming.addedAt, incoming.deprecated));
        return next;
    }

    private static String topicPrefix(String slug) {
        if (slug == null || slug.isBlank()) return "general";
        return slug.replaceAll("[^A-Za-z0-9-]", "-");
    }

    private void writeTopic(Topic t) {
        Map<String, Object> fm = t.toFrontmatter();
        fm.put("updatedAt", Instant.now().toString());
        fm.put("entryCount", t.entries.size());
        String body = t.renderBody();
        try {
            Files.createDirectories(rootDir);
            Path tmp = Files.createTempFile(rootDir, t.filename() + ".", ".tmp");
            String full = Frontmatter.render(fm) + "\n" + body;
            Files.writeString(tmp, full, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, t.path,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, t.path,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.warn("LongTermStore.writeTopic failed for {}: {}", t.path, ex.getMessage());
        }
    }

    private void replaceTopic(Topic t) {
        List<Topic> next = new ArrayList<>(topicsCache.get());
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).seq.equals(t.seq) && next.get(i).slug.equals(t.slug)) {
                next.set(i, t);
                topicsCache.set(next);
                topicBySeqSlug.put(t.seq + "-" + t.slug, t);
                return;
            }
        }
        next.add(t);
        topicsCache.set(next);
        topicBySeqSlug.put(t.seq + "-" + t.slug, t);
    }

    static Topic readTopic(Path path) {
        if (!Files.exists(path)) return null;
        try {
            String raw = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            return parseTopic(path, raw);
        } catch (Exception ex) {
            log.warn("LongTermStore.readTopic failed for {}: {}", path, ex.getMessage());
            return null;
        }
    }

    static Topic parseTopic(Path path, String raw) {
        if (raw == null || raw.isBlank()) return null;
        Map<String, Object> fm = Frontmatter.parse(raw);
        String body = raw;
        if (raw.startsWith("---")) {
            int second = raw.indexOf("\n---", 3);
            if (second > 0) {
                int bodyStart = raw.indexOf('\n', second + 4);
                if (bodyStart > 0) body = raw.substring(bodyStart + 1);
            }
        }
        String seq = stringOr(fm.get("seq"), "000");
        String slug = stringOr(fm.get("topic"), path.getFileName().toString().replaceFirst("^\\d{3}-", "").replaceFirst("\\.md$", ""));
        String summary = stringOr(fm.get("summary"), "");
        List<String> topicsList = listOr(fm.get("topics"));
        List<String> keywordsList = listOr(fm.get("keywords"));
        int importance = intOr(fm.get("importance"), 3);
        boolean pinned = boolOr(fm.get("pinned"), false);
        Instant createdAt = parseInstant(stringOr(fm.get("createdAt"), null), Instant.now());
        Instant updatedAt = parseInstant(stringOr(fm.get("updatedAt"), null), createdAt);

        // 解析正文 —— 按 # 标题分段，提取每个 yaml 块
        List<Entry> entries = new ArrayList<>();
        String[] sections = body.split("(?m)^# ");
        for (String sec : sections) {
            if (sec.isBlank()) continue;
            String head = sec.lines().findFirst().orElse("").strip();
            Category cat = null;
            for (Category c : Category.values()) if (c.display.equals(head)) { cat = c; break; }
            if (cat == null) continue;
            StringBuilder yaml = new StringBuilder();
            boolean inBlock = false;
            for (String line : sec.split("\n")) {
                if (line.strip().startsWith("```yaml")) { inBlock = true; continue; }
                if (line.strip().startsWith("```")) { inBlock = false; yaml.append("---\n"); continue; }
                if (inBlock) yaml.append(line).append("\n");
            }
            if (yaml.length() == 0) continue;
            String[] docs = yaml.toString().split("(?m)^---\\s*$");
            for (String doc : docs) {
                if (doc.isBlank()) continue;
                Entry e = parseYamlEntry(doc, cat, slug);
                if (e != null) entries.add(e);
            }
        }
        return new Topic(seq, slug, path, summary, topicsList, keywordsList, importance, pinned,
                entries.size(), createdAt, updatedAt, entries);
    }

    static Entry parseYamlEntry(String doc, Category fallbackCategory, String topic) {
        Category cat = fallbackCategory;
        String id = "";
        String content = "";
        int importance = 3;
        boolean pinned = false;
        String evidence = "";
        String reason = "";
        Instant addedAt = Instant.now();
        boolean deprecated = false;
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
                case "id" -> id = v;
                case "category" -> cat = Category.fromWire(v);
                case "content" -> content = v;
                case "importance" -> { try { importance = Integer.parseInt(v); } catch (NumberFormatException ignore) { } }
                case "pinned" -> pinned = Boolean.parseBoolean(v);
                case "evidence" -> evidence = v;
                case "reason" -> reason = v;
                case "addedAt" -> addedAt = parseInstant(v, Instant.now());
                case "deprecated" -> deprecated = Boolean.parseBoolean(v);
                default -> { }
            }
        }
        if (content.isEmpty()) return null;
        return new Entry(id, cat, content, importance, pinned, evidence, reason, topic, addedAt, deprecated);
    }

    private static String stringOr(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static int intOr(Object o, int def) {
        if (o == null) return def;
        try { return Integer.parseInt(o.toString()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolOr(Object o, boolean def) {
        if (o == null) return def;
        return Boolean.parseBoolean(o.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOr(Object o) {
        if (o == null) return new ArrayList<>();
        if (o instanceof List) return new ArrayList<>((List<String>) o);
        if (o instanceof Iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : (Iterable<?>) o) out.add(item == null ? "" : item.toString());
            return out;
        }
        return new ArrayList<>();
    }

    private static Instant parseInstant(String s, Instant def) {
        if (s == null || s.isBlank()) return def;
        try { return Instant.parse(s); } catch (Exception e) { return def; }
    }

    /** 项目根目录（主题文件所在目录）。 */
    public Path rootDir() { return rootDir; }

    /** 兼容旧接口 —— 返回第一个主题文件路径（若无则项目根目录）。 */
    public Path path() {
        List<Topic> ts = topicsCache.get();
        if (!ts.isEmpty()) return ts.get(0).path;
        return rootDir;
    }
}
package org.example.agent.context.memory;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 记忆召回评分器（part4.md §7.7）。
 *
 * <p>加权公式：
 * <pre>
 *   score = 0.45 * lexical
 *         + 0.20 * importance / 5
 *         + 0.20 * recency
 *         + 0.15 * typeWeight
 * </pre>
 *
 * <p>门控规则：
 * <ol>
 *   <li>{@code lexical == 0} → 整体 score 强制为 0（不盲目召回关键闸门）</li>
 *   <li>score &lt; threshold → 丢弃</li>
 *   <li>剩余按 score 降序取 TopN</li>
 *   <li>允许结果为空</li>
 * </ol>
 *
 * <p>召回池：长期记忆主题文件（按主题为单元）+ 非当前 session 的 mid-term；
 * 当前 session 的 mid-term 由 {@link MidTermStore} 直接注入；pinned / importance=5 的条目由
 * {@link #loadPinnedEntries()} 返回展开。
 *
 * <p>查询为空（自动步骤） → 仅返回 pinned / importance=5 的常驻条目，不参与评分。
 */
@Component
public class MemoryRecallScorer {

    private static final Pattern CAMEL_UNDERSCORE_SPLITTER =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[_\\-]+");

    private final LongTermStore longTermStore;
    private final MidTermStore midTermStore;

    private final double threshold;
    private final int topN;
    private final double halfLifeDays;

    private final double weightLexical;
    private final double weightImportance;
    private final double weightRecency;
    private final double weightType;

    public MemoryRecallScorer(LongTermStore longTermStore,
                              MidTermStore midTermStore,
                              @Value("${agent.memory.recall.threshold:0.35}") double threshold,
                              @Value("${agent.memory.recall.top-n:5}") int topN,
                              @Value("${agent.memory.recall.half-life-days:14}") double halfLifeDays,
                              @Value("${agent.memory.recall.weights.lexical:0.45}") double wLexical,
                              @Value("${agent.memory.recall.weights.importance:0.20}") double wImportance,
                              @Value("${agent.memory.recall.weights.recency:0.20}") double wRecency,
                              @Value("${agent.memory.recall.weights.type:0.15}") double wType) {
        this.longTermStore = longTermStore;
        this.midTermStore = midTermStore;
        this.threshold = threshold;
        this.topN = Math.max(1, topN);
        this.halfLifeDays = halfLifeDays;
        this.weightLexical = wLexical;
        this.weightImportance = wImportance;
        this.weightRecency = wRecency;
        this.weightType = wType;
    }

    /** 评分结果（用于渲染）。 */
    @Getter
    public static final class Scored {
        private final String title;
        private final String summary;
        private final String path;
        private final String type;     // "topic" / "session"
        private final double score;
        private final boolean pinned;
        private final List<LongTermStore.Entry> entries;   // topic 才有
        private final MidTermStore.MidTerm midTerm;       // session 才有

        public Scored(String title, String summary, String path, String type, double score,
                      boolean pinned, List<LongTermStore.Entry> entries, MidTermStore.MidTerm midTerm) {
            this.title = title == null ? "" : title;
            this.summary = summary == null ? "" : summary;
            this.path = path == null ? "" : path;
            this.type = type == null ? "topic" : type;
            this.score = score;
            this.pinned = pinned;
            this.entries = entries == null ? new ArrayList<>() : entries;
            this.midTerm = midTerm;
        }
    }

    /**
     * 主入口：评分召回。
     *
     * @param query 用户当前输入（可空 —— 为空时仅返回常驻条目）
     * @param currentSessionId 当前 session（其 mid-term 不参与评分；始终单独注入）
     */
    public List<Scored> score(String query, String currentSessionId) {
        List<Scored> pinned = loadPinnedEntries();
        if (query == null || query.isBlank()) {
            return pinned;
        }
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return pinned;

        List<Scored> scored = new ArrayList<>();

        // 长期记忆主题文件（按主题评分单元）
        if (longTermStore != null) {
            for (LongTermStore.Topic t : longTermStore.loadTopics()) {
                // 主题自身 pinned/importance=5 → 已通过 loadPinnedEntries 注入，跳过评分
                if (t.isPinned() || t.getImportance() >= 5) continue;
                double score = scoreTopic(t, queryTerms);
                if (score <= 0) continue;
                scored.add(new Scored(t.getSlug(), t.getSummary(), t.filename(),
                        "topic", score, false, t.getEntries(), null));
            }
        }

        // 非当前 session 的 mid-term
        if (midTermStore != null) {
            for (MidTermStore.MidTerm mt : loadAllMidTerms(currentSessionId)) {
                double score = scoreMidTerm(mt, queryTerms);
                if (score <= 0) continue;
                scored.add(new Scored(mt.getSessionId(), mt.getSummary(),
                        ".agent/sessions/" + mt.getSessionId() + "/mid-term.json",
                        "session", score, false, new ArrayList<>(), mt));
            }
        }

        scored.sort(Comparator.comparingDouble(Scored::getScore).reversed());
        // 阈值门控
        List<Scored> gated = new ArrayList<>();
        for (Scored s : scored) {
            if (s.getScore() < threshold) continue;
            gated.add(s);
            if (gated.size() >= topN) break;
        }

        // 拼到 pinned 之后
        List<Scored> out = new ArrayList<>();
        out.addAll(pinned);
        out.addAll(gated);
        return out;
    }

    /** 仅常驻条目（pinned=true 或 importance=5）。 */
    public List<Scored> loadPinnedEntries() {
        List<Scored> pinned = new ArrayList<>();
        if (longTermStore != null) {
            for (LongTermStore.Topic t : longTermStore.loadTopics()) {
                if (!t.isPinned() && t.getImportance() < 5) continue;
                pinned.add(new Scored(t.getSlug(), t.getSummary(), t.filename(),
                        "topic", 1.0, true, t.getEntries(), null));
            }
        }
        return pinned;
    }

    /** 渲染为紧凑列表（注入到 dynamicLayer.memory_index 时使用）。 */
    public static String render(List<Scored> scored, double threshold) {
        if (scored == null || scored.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        List<Scored> pinnedOnly = new ArrayList<>();
        List<Scored> recalled = new ArrayList<>();
        for (Scored s : scored) {
            if (s.isPinned()) pinnedOnly.add(s);
            else recalled.add(s);
        }
        if (!pinnedOnly.isEmpty()) {
            sb.append("# 常驻条目\n");
            for (Scored s : pinnedOnly) {
                sb.append("- [pinned] ").append(s.getPath()).append(" — ").append(s.getSummary()).append("\n");
                for (LongTermStore.Entry e : s.getEntries()) {
                    if (e.isDeprecated()) continue;
                    sb.append("  - [").append(e.getCategory().wire).append("] (imp=").append(e.getImportance())
                            .append(") ").append(e.getContent()).append("\n");
                }
            }
            sb.append("\n");
        }
        if (!recalled.isEmpty()) {
            sb.append("# 召回记忆 (").append(recalled.size()).append(" 条, 阈值 ").append(formatScore(threshold)).append(")\n");
            for (Scored s : recalled) {
                sb.append("- [").append(s.getType()).append(" ").append(formatScore(s.getScore()))
                        .append("] ").append(s.getPath()).append(" — ").append(s.getSummary()).append("\n");
            }
        }
        return sb.toString();
    }

    // ============ scoring ============

    double scoreTopic(LongTermStore.Topic t, List<String> queryTerms) {
        StringBuilder hay = new StringBuilder();
        hay.append(t.getSummary()).append(' ');
        for (String s : t.getTopics()) hay.append(s).append(' ');
        for (String s : t.getKeywords()) hay.append(s).append(' ');
        for (LongTermStore.Entry e : t.getEntries()) {
            if (e.isDeprecated()) continue;
            hay.append(e.getContent()).append(' ');
        }
        double lex = lexical(queryTerms, hay.toString());
        if (lex <= 0) return 0;
        double imp = t.getImportance() / 5.0;
        double rec = recency(t.getUpdatedAt());
        double typ = typeWeight("topic");
        return weightLexical * lex + weightImportance * imp + weightRecency * rec + weightType * typ;
    }

    double scoreMidTerm(MidTermStore.MidTerm mt, List<String> queryTerms) {
        StringBuilder hay = new StringBuilder();
        hay.append(mt.getSummary()).append(' ');
        for (String s : mt.getTopics()) hay.append(s).append(' ');
        for (String s : mt.getKeywords()) hay.append(s).append(' ');
        hay.append(mt.getSessionSummary()).append(' ');
        for (String s : mt.getUserFocus()) hay.append(s).append(' ');
        for (String s : mt.getContextualRules()) hay.append(s).append(' ');
        double lex = lexical(queryTerms, hay.toString());
        if (lex <= 0) return 0;
        double imp = (mt.getImportance() == 0 ? 3 : mt.getImportance()) / 5.0;
        double rec = recency(mt.getUpdatedAt());
        double typ = typeWeight("session");
        return weightLexical * lex + weightImportance * imp + weightRecency * rec + weightType * typ;
    }

    static double lexical(List<String> queryTerms, String haystack) {
        if (queryTerms == null || queryTerms.isEmpty()) return 0;
        List<String> docTerms = tokenize(haystack);
        if (docTerms.isEmpty()) return 0;
        Map<String, Integer> qf = termFreq(queryTerms);
        Map<String, Integer> df = termFreq(docTerms);
        double score = 0;
        double maxPossible = 0;
        for (Map.Entry<String, Integer> e : qf.entrySet()) {
            String term = e.getKey();
            int qWeight = e.getValue();
            maxPossible += qWeight;
            int dCount = df.getOrDefault(term, 0);
            if (dCount > 0) {
                double idf = 1.0 + Math.log(1.0 + dCount);
                score += qWeight * Math.min(1.0, dCount / 3.0) * idf;
            }
        }
        if (maxPossible <= 0) return 0;
        double normalized = score / (maxPossible * 2.5);
        if (normalized > 1) normalized = 1;
        return normalized;
    }

    static double recency(Instant updatedAt) {
        if (updatedAt == null) return 0;
        double days = Duration.between(updatedAt, Instant.now()).toMillis() / 86_400_000.0;
        if (days < 0) days = 0;
        return Math.exp(-days / 14.0);
    }

    static double typeWeight(String type) {
        if (type == null) return 0.5;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "feedback" -> 1.0;
            case "project" -> 0.9;
            case "reference" -> 0.7;
            case "user" -> 0.7;
            case "session" -> 0.5;
            default -> 0.5;
        };
    }

    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String lower = text.toLowerCase(Locale.ROOT);
        // 中文：按 2-gram 切
        for (int i = 0; i + 2 <= lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiLetterOrDigit(c)) continue;
            out.add(lower.substring(i, i + 2));
            i++; // 步进 2
        }
        // 英文：按非字母数字切，再把 camelCase/under_score 拆开
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiLetterOrDigit(c)) {
                acc.append(c);
            } else {
                if (acc.length() > 0) {
                    flushAsciiWord(out, acc);
                    acc.setLength(0);
                }
            }
        }
        if (acc.length() > 0) flushAsciiWord(out, acc);
        return out;
    }

    private static void flushAsciiWord(List<String> out, StringBuilder acc) {
        String[] parts = CAMEL_UNDERSCORE_SPLITTER.split(acc.toString());
        for (String p : parts) {
            if (!p.isEmpty()) out.add(p);
        }
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    static Map<String, Integer> termFreq(List<String> terms) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String t : terms) {
            if (t == null || t.isBlank()) continue;
            m.merge(t, 1, Integer::sum);
        }
        return m;
    }

    private List<MidTermStore.MidTerm> loadAllMidTerms(String currentSessionId) {
        // 由 Spring 在装配时通过专用方法加载（此处只暴露接口）；具体遍历由调用方提供。
        // 默认实现：从 longTermStore 拿不到 mid-term，由 ContextBuilder 单独注入 current session 的 mid-term。
        // 非当前 session 的 mid-term 由外部调用方（如 MemoryRecallAssembly）注入。
        return new ArrayList<>();
    }

    /** 给 ContextBuilder 注入其他 session 的 mid-term。 */
    public List<Scored> scoreWithExtraMidTerms(String query,
                                               String currentSessionId,
                                               List<MidTermStore.MidTerm> extraMidTerms) {
        List<Scored> base = score(query, currentSessionId);
        if (extraMidTerms == null || extraMidTerms.isEmpty()) return base;
        if (query == null || query.isBlank()) return base;
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) return base;

        List<Scored> extra = new ArrayList<>(base);
        for (MidTermStore.MidTerm mt : extraMidTerms) {
            if (currentSessionId != null && currentSessionId.equals(mt.getSessionId())) continue;
            double s = scoreMidTerm(mt, queryTerms);
            if (s <= 0 || s < threshold) continue;
            extra.add(new Scored(mt.getSessionId(), mt.getSummary(),
                    ".agent/sessions/" + mt.getSessionId() + "/mid-term.json",
                    "session", s, false, new ArrayList<>(), mt));
        }
        extra.sort((a, b) -> {
            if (a.isPinned() != b.isPinned()) return a.isPinned() ? -1 : 1;
            return Double.compare(b.getScore(), a.getScore());
        });
        // 应用 topN 截断到非 pinned
        List<Scored> pinnedOnly = new ArrayList<>();
        List<Scored> recalled = new ArrayList<>();
        for (Scored s : extra) {
            if (s.isPinned()) pinnedOnly.add(s);
            else recalled.add(s);
        }
        if (recalled.size() > topN) recalled = recalled.subList(0, topN);
        List<Scored> out = new ArrayList<>();
        out.addAll(pinnedOnly);
        out.addAll(recalled);
        return out;
    }

    private static String formatScore(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
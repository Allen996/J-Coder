package org.example.agent.context.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MemorySummarizer 的轻量 LLM 实现（part4.md §7.5 / §7.6 / §7.7）。
 *
 * <p>所有提示词从 {@link MemoryPromptRegistry} 加载（part4 §7.6）。
 * 模型调用统一走 {@link MemoryModelGateway}（part4 §7.5）。
 *
 * <p>§7.5 硬性约束：
 * <ul>
 *   <li>任何写入记忆文件的摘要性内容，必须是配置模型的产出。</li>
 *   <li>模型不可用或解析失败 → 返回 null / 空集合，不落盘低质量替代品。</li>
 *   <li>本实现<b>不</b>含任何启发式兜底路径（旧版 {@code heuristicMidTermPatch} / {@code heuristicFullMidTerm} / {@code heuristicCandidates} 全部删除）。</li>
 * </ul>
 */
@Slf4j
@Component
public class FlashMemorySummarizer implements MemorySummarizer {

    private final MemoryModelGateway gateway;
    private final MemoryPromptRegistry registry;

    /** 测试 / 单组件初始化用 —— 无 gateway，直接 no-op。 */
    public FlashMemorySummarizer() {
        this(null, null);
    }

    public FlashMemorySummarizer(MemoryModelGateway gateway, MemoryPromptRegistry registry) {
        this.gateway = gateway;
        this.registry = registry;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FlashMemorySummarizer(ApplicationContext ctx) {
        this(resolveGateway(ctx), resolveRegistry(ctx));
    }

    private static MemoryModelGateway resolveGateway(ApplicationContext ctx) {
        try {
            return ctx.getBean(MemoryModelGateway.BEAN_NAME, MemoryModelGateway.class);
        } catch (Exception ex) {
            try { return ctx.getBean(MemoryModelGateway.class); } catch (Exception ignore) { return null; }
        }
    }

    private static MemoryPromptRegistry resolveRegistry(ApplicationContext ctx) {
        try { return ctx.getBean(MemoryPromptRegistry.class); } catch (Exception ignore) { return null; }
    }

    // ============ mid-term ============

    @Override
    public MidTermPatch summarizeMidTermTurn(String sessionId,
                                             List<Message> turnMessages,
                                             MidTermStore.MidTerm previous) {
        if (turnMessages == null || turnMessages.isEmpty()) return null;
        if (gateway == null || !gateway.isAvailable() || registry == null) return null;
        String previousMd = previous == null ? "(首次写入，无既有内容)" : previous.toMarkdown();
        String transcript = renderTurn(turnMessages);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("previous", previousMd);
        vars.put("transcript", transcript);
        String raw = gateway.call(registry.require("mid_term_patch"), vars);
        if (raw == null || raw.isBlank()) return null;
        return parseMidTermPatch(raw);
    }

    @Override
    public MidTermStore.MidTerm regenerateMidTerm(String sessionId, List<Message> allSessionMessages) {
        if (allSessionMessages == null || allSessionMessages.isEmpty()) {
            return MidTermStore.MidTerm.empty(sessionId);
        }
        if (gateway == null || !gateway.isAvailable() || registry == null) return null;
        String transcript = renderTurn(allSessionMessages);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("transcript", transcript);
        String raw = gateway.call(registry.require("mid_term_regen"), vars);
        if (raw == null || raw.isBlank()) return null;
        return parseFullMidTerm(raw, sessionId);
    }

    @Override
    public List<ExtractedCandidate> extractLongTermCandidates(List<Message> unsummarizedTurns,
                                                              List<String> existingTopics,
                                                              List<String> existingTitles) {
        if (unsummarizedTurns == null || unsummarizedTurns.isEmpty()) return List.of();
        if (gateway == null || !gateway.isAvailable() || registry == null) return List.of();
        String transcript = renderTurn(unsummarizedTurns);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("conversation", transcript);
        vars.put("existing_topics", existingTopics == null ? List.of() : existingTopics);
        vars.put("existing_titles", existingTitles == null ? List.of() : existingTitles);
        String raw = gateway.call(registry.require("long_term_extract"), vars);
        if (raw == null || raw.isBlank()) return List.of();
        List<ExtractedCandidate> parsed = parseCandidatesYaml(raw);
        if (parsed == null) return List.of();
        // 仅保留 importance >= 3
        return parsed.stream().filter(c -> c.getImportance() >= 3).toList();
    }

    // ============ renderers ============

    static String renderTurn(List<Message> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Message m : msgs) {
            String role;
            if (m instanceof org.springframework.ai.chat.messages.UserMessage) role = "user";
            else if (m instanceof org.springframework.ai.chat.messages.AssistantMessage) role = "assistant";
            else if (m instanceof org.springframework.ai.chat.messages.SystemMessage) role = "system";
            else role = m.getClass().getSimpleName();
            sb.append("[").append(role).append("] ").append(org.example.agent.context.session.SessionMessageStore.extractText(m)).append("\n\n");
        }
        return sb.toString();
    }

    // ============ mid-term parsers ============

    static MidTermPatch parseMidTermPatch(String raw) {
        if (raw == null) return null;
        String text = stripJsonFence(raw);
        Map<String, Object> json = parseLooseJson(text);
        if (json == null) return null;
        List<String> done = stringList(json.get("crossSessionProgress"), "done");
        List<String> inProgress = stringList(json.get("crossSessionProgress"), "inProgress");
        List<String> blocked = stringList(json.get("crossSessionProgress"), "blocked");
        String summary = asString(json.get("sessionSummary"));
        List<String> userFocus = stringListFrom(json.get("userFocus"));
        List<String> rules = stringListFrom(json.get("contextualRules"));
        // "无" 视作空
        if ("无".equals(summary)) summary = null;
        MidTermPatch patch = new MidTermPatch(done, inProgress, blocked, summary, userFocus, rules);
        return patch.isEmpty() ? MidTermPatch.empty() : patch;
    }

    static MidTermStore.MidTerm parseFullMidTerm(String raw, String sessionId) {
        if (raw == null) return null;
        String text = stripJsonFence(raw);
        Map<String, Object> json = parseLooseJson(text);
        if (json == null) return null;
        List<String> done = stringList(json.get("crossSessionProgress"), "done");
        List<String> inProgress = stringList(json.get("crossSessionProgress"), "inProgress");
        List<String> blocked = stringList(json.get("crossSessionProgress"), "blocked");
        String sessionSummary = asString(json.get("sessionSummary"));
        if (sessionSummary == null) sessionSummary = "";
        List<String> userFocus = stringListFrom(json.get("userFocus"));
        List<String> rules = stringListFrom(json.get("contextualRules"));
        String summary = asString(json.get("summary"));
        if (summary == null) summary = sessionSummary;
        List<String> topics = stringListFrom(json.get("topics"));
        List<String> keywords = stringListFrom(json.get("keywords"));
        Integer importance = asInt(json.get("importance"));
        return new MidTermStore.MidTerm(sessionId,
                done, inProgress, blocked,
                sessionSummary, userFocus, rules,
                summary, topics, keywords,
                importance == null ? 3 : importance,
                Instant.now());
    }

    // ============ candidate YAML parser ============

    private static final Pattern CANDIDATE_ITEM =
            Pattern.compile("(?ms)^\\s*-\\s*category:\\s*(\\S+)\\s*\\R(?:\\s*title:\\s*(.*?)\\R)?(?:\\s*content:\\s*(.+?)\\R)?(?:\\s*topic:\\s*(.*?)\\R)?(?:\\s*topicProposal:\\s*(.*?)\\R)?\\s*importance:\\s*(\\d+)\\s*\\R(?:\\s*pinned:\\s*(\\w+)\\s*\\R)?(?:\\s*evidence:\\s*(.*?))?\\s*(?:\\R\\s*reason:\\s*(.*?))?(?=\\s*\\R\\s*-|\\s*\\R*$)");

    static List<ExtractedCandidate> parseCandidatesYaml(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String text = raw;
        int start = text.indexOf("```");
        if (start >= 0) {
            int end = text.indexOf("```", start + 3);
            if (end > start) text = text.substring(start + 3, end).trim();
        }
        List<ExtractedCandidate> out = new ArrayList<>();
        Matcher m = CANDIDATE_ITEM.matcher(text);
        while (m.find()) {
            try {
                String catRaw = m.group(1) == null ? "feedback" : m.group(1).strip();
                String title = m.group(2) == null ? "" : m.group(2).strip();
                String content = m.group(3) == null ? "" : m.group(3).strip();
                String topic = m.group(4) == null ? "NEW" : m.group(4).strip();
                String topicProposal = m.group(5) == null ? "" : m.group(5).strip();
                String importanceRaw = m.group(6) == null ? "3" : m.group(6).strip();
                String pinnedRaw = m.group(7) == null ? "false" : m.group(7).strip();
                String evidence = m.group(8) == null ? "" : m.group(8).strip();
                String reason = m.group(9) == null ? "" : m.group(9).strip();
                int importance = Integer.parseInt(importanceRaw);
                boolean pinned = Boolean.parseBoolean(pinnedRaw);
                if (content.startsWith("\"") && content.endsWith("\"")) {
                    content = content.substring(1, content.length() - 1);
                }
                LongTermStore.Category cat = LongTermStore.Category.fromWire(catRaw);
                out.add(new ExtractedCandidate(cat, title, content, importance, pinned,
                        topic, topicProposal, evidence, reason));
            } catch (Exception ignore) {
                // 忽略错误条目，继续解析
            }
        }
        return out;
    }

    // ============ JSON utilities ============

    /** 去掉 ```json ... ``` 包裹（兼容部分模型自动加 fence 的情况）。 */
    static String stripJsonFence(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) {
                int closing = t.lastIndexOf("```");
                if (closing > firstNewline) {
                    t = t.substring(firstNewline + 1, closing).strip();
                }
            }
        }
        return t;
    }

    /** 极简 JSON 解析 —— 只覆盖本项目 LLM 输出形态（对象 / 字符串 / 数字 / 数组）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseLooseJson(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(text, Map.class);
        } catch (Exception ex) {
            log.debug("parseLooseJson failed: {}", ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> stringList(Object root, String key) {
        if (root instanceof Map) {
            Object v = ((Map<String, Object>) root).get(key);
            return stringListFrom(v);
        }
        return new ArrayList<>();
    }

    static List<String> stringListFrom(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof List) {
            for (Object item : (List<?>) v) {
                String s = asString(item);
                if (s != null && !s.isBlank() && !"无".equals(s.strip())) out.add(s);
            }
        } else {
            String s = asString(v);
            if (s != null && !s.isBlank() && !"无".equals(s.strip())) out.add(s);
        }
        return out;
    }

    static String asString(Object v) {
        if (v == null) return null;
        return v.toString();
    }

    static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
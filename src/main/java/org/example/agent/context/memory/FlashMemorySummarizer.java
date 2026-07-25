package org.example.agent.context.memory;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.memory.config.LightweightChatModelConfig;
import org.example.agent.context.session.SessionMessageStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MemorySummarizer 的轻量 LLM 实现（part4.md §7.2 / §7.7）。
 *
 * <p>使用项目专属的 {@code memoryChatModel}（默认 qwen-flash）：
 * <ul>
 *   <li>{@link #summarizeMidTermTurn} —— 让 LLM 输出 JSON 风格补丁（含 5 个字段）；解析失败则用启发式。</li>
 *   <li>{@link #regenerateMidTerm} —— 让 LLM 重新组织整个 session 为 5 字段；解析失败则聚合既有轮次拼装。</li>
 *   <li>{@link #extractLongTermCandidates} —— 使用 part4.md §7.7 系统提示词；解析 YAML 列表失败时按原文截断作为唯一候选。</li>
 * </ul>
 *
 * <p>失败的兜底不是抛异常，而是返回合理值，保证主对话流不被阻塞。
 */
@Slf4j
@Component
public class FlashMemorySummarizer implements MemorySummarizer {

    private static final int MAX_OUTPUT_TOKENS = 1024;

    private final ChatModel model;

    /** 测试 / 单组件初始化用 —— 跳过 LLM,只用启发式。 */
    public FlashMemorySummarizer() {
        this(null);
    }

    @Autowired
    public FlashMemorySummarizer(org.springframework.context.ApplicationContext ctx) {
        ChatModel resolved = null;
        try {
            resolved = ctx.getBean(LightweightChatModelConfig.BEAN_NAME, ChatModel.class);
        } catch (Exception ignore) { }
        this.model = resolved;
        if (this.model == null) {
            log.info("FlashMemorySummarizer: no memoryChatModel bean available, will use heuristic fallback only");
        }
    }

    // ============ mid-term ============

    @Override
    public MidTermPatch summarizeMidTermTurn(String sessionId,
                                             List<Message> turnMessages,
                                             MidTermStore.MidTerm previous) {
        if (turnMessages == null || turnMessages.isEmpty()) {
            return MidTermPatch.empty();
        }
        String previousMd = previous == null ? "(首次写入，无既有内容)" : previous.toMarkdown();
        String transcript = renderTurn(turnMessages);
        String system = MID_TERM_PATCH_SYSTEM_PROMPT;
        String user = MID_TERM_PATCH_USER_TEMPLATE
                .replace("{previous}", previousMd)
                .replace("{transcript}", transcript);
        String raw = chatOrNull(system, user);
        if (raw == null || raw.isBlank()) {
            return heuristicMidTermPatch(transcript);
        }
        MidTermPatch parsed = parseMidTermPatch(raw);
        return parsed == null ? heuristicMidTermPatch(transcript) : parsed;
    }

    @Override
    public MidTermStore.MidTerm regenerateMidTerm(String sessionId, List<Message> allSessionMessages) {
        if (allSessionMessages == null || allSessionMessages.isEmpty()) {
            return new MidTermStore.MidTerm(sessionId, "", "", "", "", "", Instant.now());
        }
        String transcript = renderTurn(allSessionMessages);
        String system = MID_TERM_REGEN_SYSTEM_PROMPT;
        String user = MID_TERM_REGEN_USER_TEMPLATE.replace("{transcript}", transcript);
        String raw = chatOrNull(system, user);
        if (raw != null && !raw.isBlank()) {
            MidTermStore.MidTerm parsed = parseFullMidTerm(raw, sessionId);
            if (parsed != null) return parsed;
        }
        return heuristicFullMidTerm(sessionId, transcript);
    }

    // ============ long-term candidates ============

    @Override
    public List<ExtractedCandidate> extractLongTermCandidates(List<Message> unsummarizedTurns) {
        if (unsummarizedTurns == null || unsummarizedTurns.isEmpty()) {
            return List.of();
        }
        String transcript = renderTurn(unsummarizedTurns);
        String system = LONG_TERM_EXTRACT_SYSTEM_PROMPT;
        String user = LONG_TERM_EXTRACT_USER_TEMPLATE.replace("{conversation}", transcript);
        String raw = chatOrNull(system, user);
        if (raw == null || raw.isBlank()) {
            return heuristicCandidates(transcript);
        }
        List<ExtractedCandidate> parsed = parseCandidatesYaml(raw);
        if (parsed == null || parsed.isEmpty()) {
            return heuristicCandidates(transcript);
        }
        // 仅保留 importance >= 3
        return parsed.stream().filter(c -> c.getImportance() >= 3).toList();
    }

    // ============ prompt library ============

    static final String MID_TERM_PATCH_SYSTEM_PROMPT = """
            你是会话摘要助手。你已经看到了当前 session 的 mid-term 既有总结,以及刚发生的一轮对话。
            你的任务是用结构化中文输出一份「增量补丁」,描述本轮相对既有 mid-term 的变更。
            不要凭空捏造事实,不要重复既有条目;只输出有信息增量的部分。

            严格输出五个字段(每行一个标签),顺序固定:
              session_goal: 如果本轮明确了 session 主旨与既有不同,在此给出新值;否则跳过本字段
              delta_completed: 本轮新完成的事,1-2 句,无新增写"无"
              delta_decisions: 本轮做出的关键决策,1-2 句,无新增写"无"
              delta_lessons: 本轮暴露的教训/踩坑,1-2 句,无新增写"无"
              delta_pending_todos: 本轮新增的后续待办,1-2 句,无新增写"无"

            规则:
              - 不要编造文件路径、函数名、数字。
              - 已有的 mid-term 内容不要复述,只描述增量。
              - 若整轮都没有信息增量,五个字段全部写"无"。
              - 用中文输出,客观、第三人称。
            """;

    static final String MID_TERM_PATCH_USER_TEMPLATE = """
            【既有 mid-term】
            {previous}

            【本轮对话】
            {transcript}

            请按 system 规则输出增量补丁。
            """;

    static final String MID_TERM_REGEN_SYSTEM_PROMPT = """
            你是会话摘要助手。你的任务是把整个 session 的对话重新整理成五字段结构化 mid-term:
              session_goal: 用 1-2 句话描述整个 session 的目标或主旨。
              completed: 已经完成的标志性事项,Markdown 列表,3-8 条;若整轮空,写"无"。
              decisions: 关键决策列表;每条 1 行;无写"无"。
              lessons: 本次会话暴露的教训/踩坑;每条 1 行;无写"无"。
              pending_todos: 后续待办;每条 1 行;无写"无"。

            规则:
              - 客观、第三人称陈述,中文输出。
              - 不要复述每轮细节;要的是结构化总结。
              - 不要编造文件路径/数字;只引用对话里出现的具体名称。
            """;

    static final String MID_TERM_REGEN_USER_TEMPLATE = """
            【整 session 对话】
            {transcript}

            请重新总结为 5 字段 mid-term。
            """;

    /** Part4 §7.7 长期记忆提取系统提示词。 */
    static final String LONG_TERM_EXTRACT_SYSTEM_PROMPT = """
            你是一个项目记忆分析师。你的任务是审查一段完整的开发会话对话,从中提取应该永久记录到项目骨架(Nico.md)的关键信息。

            ## Nico.md 收录范围

            只记录以下类型的信息:
            1. 项目红线: 禁止事项(不要 X、不要 Y)
            2. 编程风格约定: 代码风格、命名约定、文件组织规则
            3. 关键技术决策: 已确定的技术选型、架构决策
            4. 依赖与工具: 引入的新依赖、工具配置变更
            5. 重要约定: 团队约定、协作规则

            不要记录:
              - 一次性任务细节
              - 临时调试信息
              - 个人偏好(除非是项目级约定)
              - 已有内容的简单重复

            ## 输出格式

            以 YAML 列表输出候选记忆条目,每条包含:
              - category: red_line | coding_style | decision | dependency | convention
              - content: 精炼描述,1-2 句话
              - importance: 1-5 的整数评分
              - evidence: 对话中相关原文引用,1-2 句话
              - reason: 为什么值得记录,1 句话

            如果没有值得记录的内容,返回空列表 []。

            ## 重要性评分标准

              - 5: 关键红线/决策,违反会导致严重后果
              - 4: 重要约定,团队普遍遵守
              - 3: 一般性建议,有参考价值
              - 2: 个例,价值有限
              - 1: 边缘,基本无用

            仅输出 importance >= 3 的条目。
            """;

    static final String LONG_TERM_EXTRACT_USER_TEMPLATE = """
            以下是本轮对话的完整内容,请按上述规则提取候选长期记忆条目:

            {conversation}
            """;

    // ============ LLM invocation ============

    private String chatOrNull(String systemPrompt, String userPrompt) {
        if (model == null) return null;
        try {
            List<Message> msgs = List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt));
            ChatResponse response = model.call(new Prompt(msgs));
            if (response == null || response.getResult() == null) return null;
            AssistantMessage out = response.getResult().getOutput();
            return out == null ? null : out.getText();
        } catch (Exception ex) {
            log.warn("MemorySummarizer LLM call failed: {}", ex.getMessage());
            return null;
        }
    }

    // ============ renderers ============

    static String renderTurn(List<Message> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Message m : msgs) {
            String role;
            if (m instanceof UserMessage) role = "user";
            else if (m instanceof AssistantMessage) role = "assistant";
            else if (m instanceof SystemMessage) role = "system";
            else role = m.getClass().getSimpleName();
            sb.append("[").append(role).append("] ").append(SessionMessageStore.extractText(m)).append("\n\n");
        }
        return sb.toString();
    }

    // ============ mid-term parsers ============

    static MidTermPatch parseMidTermPatch(String raw) {
        if (raw == null) return null;
        String text = raw;
        String goal = readField(text, "session_goal");
        String completed = readField(text, "delta_completed");
        String decisions = readField(text, "delta_decisions");
        String lessons = readField(text, "delta_lessons");
        String pending = readField(text, "delta_pending_todos");

        boolean hasAny = false;
        for (String v : new String[]{completed, decisions, lessons, pending}) {
            if (v != null && !v.isBlank() && !"无".equals(v.strip())) {
                hasAny = true; break;
            }
        }
        boolean goalPresent = goal != null && !goal.isBlank() && !"无".equals(goal.strip());
        if (!hasAny && !goalPresent) return MidTermPatch.empty();

        return new MidTermPatch(
                completed == null ? "" : completed,
                decisions == null ? "" : decisions,
                lessons == null ? "" : lessons,
                pending == null ? "" : pending,
                goalPresent ? goal : null);
    }

    static MidTermStore.MidTerm parseFullMidTerm(String raw, String sessionId) {
        if (raw == null) return null;
        String goal = readField(raw, "session_goal");
        String completed = readField(raw, "completed");
        String decisions = readField(raw, "decisions");
        String lessons = readField(raw, "lessons");
        String pending = readField(raw, "pending_todos");
        if (goal == null && completed == null && decisions == null && lessons == null && pending == null) {
            return null;
        }
        return new MidTermStore.MidTerm(sessionId,
                goal == null ? "" : goal,
                completed == null ? "" : completed,
                decisions == null ? "" : decisions,
                lessons == null ? "" : lessons,
                pending == null ? "" : pending,
                Instant.now());
    }

    /**
     * 解析一行 {@code key: value}，value 跨行延续直到下一个 {@code <其他key>:}
     * （不论 key 是否在白名单）。
     * 对未知 keys 返回 null（视为不存在）。
     *
     * <p>大小写不敏感匹配（key）但 value 保留原大小写：通过在原文做 case-insensitive
     * 匹配定位，再用 text 切片读真实内容。
     */
    static String readField(String text, String key) {
        Pattern p = Pattern.compile("(?im)^\\s*" + Pattern.quote(key) + "\\s*:\\s*(.*)$");
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        int matchEnd = m.end();
        StringBuilder sb = new StringBuilder(m.group(1).trim());
        String[] lines = text.substring(matchEnd).split("\n");
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains(":")) {
                String firstWord = trimmed.substring(0, trimmed.indexOf(':')).strip().toLowerCase(Locale.ROOT);
                if (firstWord.matches("[a-z_]+")) {
                    break;
                }
            }
            sb.append(' ').append(trimmed);
        }
        String result = sb.toString().strip();
        if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    // ============ heuristic fallbacks ============

    static MidTermPatch heuristicMidTermPatch(String transcript) {
        String firstUser = Arrays.stream(transcript.split("\n"))
                .filter(l -> l.startsWith("[user]"))
                .findFirst()
                .map(l -> l.replaceFirst("^\\[user\\]\\s*", ""))
                .orElse("");
        String head = firstUser.length() > 80 ? firstUser.substring(0, 80) + "..." : firstUser;
        return new MidTermPatch(
                "- 用户提出:" + head,
                "无",
                "无",
                "无",
                null);
    }

    static MidTermStore.MidTerm heuristicFullMidTerm(String sessionId, String transcript) {
        String goal = Arrays.stream(transcript.split("\n"))
                .filter(l -> l.startsWith("[user]"))
                .findFirst()
                .map(l -> l.replaceFirst("^\\[user\\]\\s*", ""))
                .map(s -> s.length() > 80 ? s.substring(0, 80) + "..." : s)
                .orElse("(无可识别目标)");
        StringBuilder completed = new StringBuilder();
        String[] userLines = transcript.split("(?m)^\\[user\\]");
        for (int i = 1; i < Math.min(userLines.length, 6); i++) {
            String firstLine = userLines[i].split("\n", 2)[0];
            if (!firstLine.isBlank()) {
                completed.append("- 处理用户输入: ")
                        .append(firstLine.length() > 60 ? firstLine.substring(0, 60) + "..." : firstLine)
                        .append("\n");
            }
        }
        if (completed.length() == 0) completed.append("无");
        return new MidTermStore.MidTerm(sessionId, goal, completed.toString().strip(),
                "无", "无", "无", Instant.now());
    }

    static List<ExtractedCandidate> heuristicCandidates(String transcript) {
        // 启发式兜底：在 transcript 中检测明显的"记住/不要"触发语句
        List<ExtractedCandidate> out = new ArrayList<>();
        String lower = transcript.toLowerCase(Locale.ROOT);
        if (lower.contains("记住") || lower.contains("记一下") || lower.contains("don't forget")) {
            String hit = excerptContaining(transcript, "记住", "记一下", "don't forget");
            out.add(new ExtractedCandidate(LongTermStore.Category.CONVENTION,
                    "用户在对话中提到要记住的事项：" + hit,
                    3, hit, "启发式检测"));
        }
        return out;
    }

    private static String excerptContaining(String text, String... keys) {
        for (String k : keys) {
            int idx = text.indexOf(k);
            if (idx >= 0) {
                int end = Math.min(text.length(), idx + 80);
                return text.substring(idx, end).replace('\n', ' ');
            }
        }
        return "";
    }

    // ============ candidate YAML parser ============

    private static final Pattern CANDIDATE_ITEM =
            Pattern.compile("(?ms)\\s*-\\s*category:\\s*(\\S+)\\s*\\R\\s*content:\\s*(.+?)\\R\\s*importance:\\s*(\\d+)(?:\\s*\\R\\s*evidence:\\s*(.*?))?(?:\\s*\\R\\s*reason:\\s*(.*?))?(?=\\s*\\R\\s*-|\\s*\\R*$)");

    static List<ExtractedCandidate> parseCandidatesYaml(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        // 直接把可能含 ```yaml 包裹的文本剥掉
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
                String catRaw = m.group(1).strip();
                String content = m.group(2).strip();
                int importance = Integer.parseInt(m.group(3).strip());
                String evidence = m.group(4) == null ? "" : m.group(4).strip();
                String reason = m.group(5) == null ? "" : m.group(5).strip();
                if (content.startsWith("\"") && content.endsWith("\"")) {
                    content = content.substring(1, content.length() - 1);
                }
                LongTermStore.Category cat = mapCategory(catRaw);
                out.add(new ExtractedCandidate(cat, content, importance, evidence, reason));
            } catch (Exception ignore) {
                // 忽略错误条目，继续解析
            }
        }
        return out;
    }

    static LongTermStore.Category mapCategory(String wire) {
        if (wire == null) return LongTermStore.Category.CONVENTION;
        String w = wire.trim().toLowerCase(Locale.ROOT);
        return switch (w) {
            case "red_line" -> LongTermStore.Category.RED_LINE;
            case "coding_style" -> LongTermStore.Category.CODING_STYLE;
            case "decision" -> LongTermStore.Category.DECISION;
            case "dependency" -> LongTermStore.Category.DEPENDENCY;
            case "convention" -> LongTermStore.Category.CONVENTION;
            default -> LongTermStore.Category.CONVENTION;
        };
    }
}

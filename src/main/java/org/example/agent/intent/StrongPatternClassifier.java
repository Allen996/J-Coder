package org.example.agent.intent;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 关键词层"强 pattern"分类器。设计稿 §3.3 扩展位:识别用户输入里"明显不属于
 * 编程任务"的几类典型模式,用于驱动 {@link IntentGate} 的反问 / 模板化响应路径。
 *
 * <p>本类只关心"信号存在性",不参与打分 —— 不返回置信度,只返回命中的 pattern 类型
 * 集合。决策逻辑在 {@link IntentGate} 中组装(强 pattern ∩ LLM primary ∩ 关键词层无编程意图
 * 才触发反问)。
 *
 * <p><b>第三阶段引入</b>。覆盖三类:
 * <ul>
 *   <li>{@link PatternType#GREETING} —— 纯寒暄/问候/致谢,无任务负载</li>
 *   <li>{@link PatternType#INJECTION} —— 试图绕开 system prompt 或冒充其他角色</li>
 *   <li>{@link PatternType#NEGATION} —— 用户主动终止/拒绝/取消</li>
 * </ul>
 *
 * <p><b>NEGATION 误伤策略</b>(已与用户确认方案 A):只匹配"纯否定"形态 ——
 * 句首/独立 token 命中。复合结构如"别忘了改 X"会漏掉 NEGATION,交由 LLM 自行
 * 分类为 WRITE_PROJECT,反问路径不触发。理由:NEGATION 反问模板是"好的,收到,
 * 有什么编程问题我可以帮你吗?",若"别忘了"被误命中触发,会打断用户原本的
 * 编程意图。
 *
 * <p>Pattern 列表以"白名单 + 精确正则"形式维护,后续如需扩展必须附带 positive /
 * negative 测试用例并跑 golden.jsonl 验证命中率。
 */
@Component
public class StrongPatternClassifier {

    /**
     * 强 pattern 类型。命名按"用户行为"而非"语义",便于 IntentGate 反问模板分支。
     */
    public enum PatternType {
        GREETING,
        INJECTION,
        NEGATION;

        /** 中文动作标签,反问模板展示用。 */
        public String chineseHint() {
            return switch (this) {
                case GREETING -> "问候";
                case INJECTION -> "角色重置";
                case NEGATION -> "取消请求";
            };
        }
    }

    /**
     * 单条命中记录。pattern 用正则;hits 给日志/调试展示。
     */
    public record PatternHit(PatternType type, String pattern, String matchedText) {}

    /**
     * 一次扫描的结果。matchedTypes() 是关键消费者字段。
     */
    public record ClassificationResult(
            Set<PatternType> matchedTypes,
            List<PatternHit> hits,
            boolean empty
    ) {
        public boolean matches(PatternType t) {
            return matchedTypes.contains(t);
        }
    }

    // ----------------- GREETING -----------------

    private static final List<Pattern> GREETING_PATTERNS = List.of(
            // 中文: 你好 / 你好啊 / 你好呀
            Pattern.compile("^\\s*你好[啊呀]?\\s*[.。!！~～]?\\s*$"),
            // 中文: 早上好 / 中午好 / 晚上好(支持"呀/呢/啊"语气助词 + 标点)
            Pattern.compile("^\\s*(早上|中午|晚上|下午)好\\s*[.。!！~～]?[啊呀呢哈哦]?\\s*$"),
            // 中文: 初次见面 / 幸会
            Pattern.compile("^\\s*(初次见面|幸会)\\s*[.。!！~～]?\\s*$"),
            // 中文: 谢谢 / 谢谢你 / 谢啦
            Pattern.compile("^\\s*(谢谢|谢谢你|谢啦|多谢)\\s*[.。!！~～]?\\s*$"),
            // 英文: hi / hello / hey / hi there(独立 token 起头,句中"hi alice"也算)
            Pattern.compile("(?i)^\\s*(hi|hello|hey)(\\s|there\\b|[,.;:!]|$)"),
            Pattern.compile("(?i)\\bhi there\\b"),
            // 英文: thank you / thanks / thx
            Pattern.compile("(?i)^\\s*(thank you|thanks|thx)\\s*[.!]?\\s*$")
    );

    // ----------------- INJECTION -----------------

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            // 中文: 忽略/忘记/丢弃 (之前/以上/上面/所有) (指令/规则/提示/约束/要求)
            Pattern.compile("(忽略|忘记|丢弃|无视|忽略掉).{0,6}(之前|以上|上面|此前|前述|所有|先前|原始).{0,6}(指令|规则|提示|约束|要求|命令|设定)"),
            // 中文: 你是/你是现在 (GPT/ChatGPT/Claude/文心/通义/...)
            Pattern.compile("(你(是|现在(是)?)|扮演|假装|装成).{0,4}(GPT|ChatGPT|Claude|文心一言|通义千问|Gemini|Llama|Bard|Anthropic|OpenAI)"),
            // 中文: 假装你是 / 假装成
            Pattern.compile("假装(你(是)?|成|你是|你是)"),
            // 英文 prompt 注入
            Pattern.compile("(?i)<\\s*(system|assistant|user)\\s*>"),
            Pattern.compile("(?i)\\b(system override|developer mode|god mode)\\b"),
            Pattern.compile("(?i)\\b(jailbreak|DAN)\\b"),
            // 英文: ignore/forget previous instructions(支持 instructions/rules 复数)
            Pattern.compile("(?i)\\b(ignore|forget|disregard)\\b.{0,15}\\b(previous|above|prior|all)\\b.{0,15}\\b(instructions?|prompts?|directives?|rules?)\\b")
    );

    // ----------------- NEGATION -----------------

    private static final List<Pattern> NEGATION_PATTERNS = List.of(
            // 中文: 句首独立"别" —— 避免"别忘了"误伤(方案 A)
            Pattern.compile("^\\s*别\\s*[.。!！~～]?\\s*$"),
            // 中文: 句首/独立"不要/别要/不必"(支持"了/哈/哦"语气助词)
            Pattern.compile("^\\s*(不要|别要|不必)\\s*[.。!！~～]?[了哈哦]?\\s*$"),
            // 中文: 句首独立"停/算了/取消"
            Pattern.compile("^\\s*(停|算了|取消|终止|中止|打住)\\s*[.。!！~～]?\\s*$"),
            // 英文: do not / don't 起句
            Pattern.compile("(?i)^\\s*(do not|don't|stop|cancel|abort|halt|nope|nah|no)\\s*[.!]?\\s*$")
    );

    private static final Map<PatternType, List<Pattern>> PATTERNS = new LinkedHashMap<>();
    static {
        PATTERNS.put(PatternType.GREETING, GREETING_PATTERNS);
        PATTERNS.put(PatternType.INJECTION, INJECTION_PATTERNS);
        PATTERNS.put(PatternType.NEGATION, NEGATION_PATTERNS);
    }

    /**
     * 扫描输入,返回所有命中的强 pattern。
     *
     * @param userInput 用户原始输入(可能为 null)
     * @return 三类 pattern 各自的命中集合。null/空串返回空集合 + empty=true。
     */
    public ClassificationResult classify(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return new ClassificationResult(EnumSet.noneOf(PatternType.class), List.of(), true);
        }

        EnumSet<PatternType> matched = EnumSet.noneOf(PatternType.class);
        java.util.List<PatternHit> hits = new java.util.ArrayList<>();

        for (Map.Entry<PatternType, List<Pattern>> e : PATTERNS.entrySet()) {
            for (Pattern p : e.getValue()) {
                java.util.regex.Matcher m = p.matcher(userInput);
                if (m.find()) {
                    matched.add(e.getKey());
                    hits.add(new PatternHit(e.getKey(), p.pattern(), m.group()));
                    // 同类型下命中任一即可,不重复添加
                    break;
                }
            }
        }

        return new ClassificationResult(matched, List.copyOf(hits), matched.isEmpty());
    }

    /**
     * 便捷方法:是否命中任意强 pattern。IntentGate 集成时用。
     */
    public boolean matchesAny(String userInput) {
        return !classify(userInput).empty();
    }

    /**
     * 便捷方法:是否命中指定类型。模板响应分支用。
     */
    public boolean matches(String userInput, PatternType type) {
        return classify(userInput).matches(type);
    }
}
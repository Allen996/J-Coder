package org.example.agent.intent;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位完整性检查。设计稿 §3.4。
 *
 * <p>每个类别预设必填槽位,槽位缺位会主动压低置信度(规则化扣分,
 * 不是 LLM 自评)。
 *
 * <ul>
 *   <li>READ_CODE: target(对象)、aspect(关注点,可空)</li>
 *   <li>WRITE_PROJECT: target_file / target_symbol、change_type、scope</li>
 *   <li>RUN_COMMAND: action(args 可选)</li>
 *   <li>CHAT_QA: 无必填(继承原 OFF_TOPIC 语义:问候/拒绝/跑题都不强制槽位)</li>
 *   <li>PLANNING: goal、constraints、acceptance</li>
 * </ul>
 */
@Component
public class SlotCompletenessChecker {

    /** 必填槽位;空 list 表示该类别没有强制槽位。 */
    private static final Map<IntentLabel, List<String>> REQUIRED = new HashMap<>();
    static {
        REQUIRED.put(IntentLabel.READ_CODE, List.of("target"));
        REQUIRED.put(IntentLabel.WRITE_PROJECT, List.of("target_file", "change_type"));
        REQUIRED.put(IntentLabel.RUN_COMMAND, List.of("action"));
        REQUIRED.put(IntentLabel.CHAT_QA, List.of());
        REQUIRED.put(IntentLabel.PLANNING, List.of("goal"));
    }

    /** 启发式抽槽位(中文输入)。规则简单粗暴,L1 时只用作参考。 */
    private static final Pattern FILE_PATTERNS = Pattern.compile(
            "[\\w./\\\\-]+\\.(java|kt|scala|py|ts|tsx|js|jsx|go|rs|rb|php|sh|yaml|yml|json|md|txt|sql|c|cpp|h|hpp|cs|xml|html|toml|gradle|properties)\\b",
            Pattern.CASE_INSENSITIVE);

    public double completeness(IntentLabel label, Map<String, Object> slots, String rawInput) {
        if (label == null) return 0.5;
        List<String> required = REQUIRED.getOrDefault(label, List.of());
        if (required.isEmpty()) return 1.0;

        Map<String, Object> filled = slots == null ? Map.of() : slots;
        int present = 0;
        for (String key : required) {
            if (hasValue(filled, key)) {
                present++;
                continue;
            }
            // 启发式回退:如果 LLM 没填,但原始输入里有文件名,补 target_file
            if ("target_file".equals(key) && rawInput != null && FILE_PATTERNS.matcher(rawInput).find()) {
                present++;
            }
        }
        return (double) present / required.size();
    }

    private boolean hasValue(Map<String, Object> filled, String key) {
        Object v = filled.get(key);
        if (v == null) return false;
        if (v instanceof String s) return !s.isBlank();
        return true;
    }

    public List<String> requiredSlots(IntentLabel label) {
        if (label == null) return List.of();
        return REQUIRED.getOrDefault(label, List.of());
    }
}
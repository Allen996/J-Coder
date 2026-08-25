package org.example.agent.intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 槽位结构性校验器。<b>方案 B 第一阶段,与 {@link SlotCompletenessChecker} 并行</b>。
 *
 * <p><b>职责区分</b>:
 * <ul>
 *   <li>{@link SlotCompletenessChecker}: 算 {@code completeness} 数值 ∈ [0,1],
 *       历史用途是作为 {@link LocalIntentScorer} 的加权分量。本类不改它的行为。</li>
 *   <li>{@code SlotCompletenessValidator}(本类): 对 LLM 抽出的 {@code slots} Map
 *       做<b>结构性</b>检查,产出"是否可执行"的二元判断,作为 L1 tier 的<b>准入门槛</b>。
 *       不参与 conf 加权,只决定 tier。</li>
 * </ul>
 *
 * <p><b>校验规则</b>(每类意图):
 * <ul>
 *   <li>{@link IntentLabel#WRITE_PROJECT}: {@code target_file} 必须像文件路径
 *       (复用 {@link SlotCompletenessChecker} 的 {@code FILE_PATTERNS});
 *       {@code change_type} 必须在已知枚举内。</li>
 *   <li>{@link IntentLabel#RUN_COMMAND}: {@code action} 必须在已知动作枚举内。</li>
 *   <li>{@link IntentLabel#READ_CODE}: {@code target} 任意非空字符串(最弱校验)。</li>
 *   <li>{@link IntentLabel#PLANNING}: {@code goal} 任意非空字符串(最弱校验)。</li>
 *   <li>{@link IntentLabel#CHAT_QA} / {@link IntentLabel#OFF_TOPIC}: 无必填,永远通过。</li>
 * </ul>
 *
 * <p><b>missing vs invalid 区分</b>:
 * <ul>
 *   <li>{@code missingSlots}: 必填槽在 {@code slots} Map 里压根没有。</li>
 *   <li>{@code invalidSlots}: 存在但结构性校验失败(如 {@code target_file="hello world"})。</li>
 * </ul>
 * <p>{@code allPassed = missing.isEmpty() && invalid.isEmpty()}。
 *
 * <p><b>使用方式</b>: L1 在 tier 决策后调用本类;若 primary ∈ {WRITE_PROJECT, RUN_COMMAND}
 * 且 {@code allPassed=false},则 conf 应被压到 offer 阈值以下,使其落入 OFFER tier,
 * 强制要求用户补全参数。这是 L1 的产品安全护栏,不是分类准确率工具。
 *
 * <p>本类是<b>纯 POJO</b>,不依赖 Spring,便于单测和评测脚本注入。
 */
public final class SlotCompletenessValidator {

    /**
     * 文件路径正则。复用 {@link SlotCompletenessChecker#FILE_PATTERNS} 的同款 regex
     * (后者的 {@code FILE_PATTERNS} 是 {@code private static},无法直接复用,
     * 这里复制同一份以避免改动既有代码的 package 边界)。
     */
    private static final Pattern FILE_PATTERNS = Pattern.compile(
            "[\\w./\\\\-]+\\.(java|kt|scala|py|ts|tsx|js|jsx|go|rs|rb|php|sh|yaml|yml|json|md|txt|sql|c|cpp|h|hpp|cs|xml|html|toml|gradle|properties)\\b",
            Pattern.CASE_INSENSITIVE);

    /** WRITE_PROJECT.change_type 允许值。case-insensitive 比较。 */
    private static final Set<String> CHANGE_TYPES = Set.of(
            "add", "delete", "rename", "refactor", "fix", "optimize", "change", "edit", "modify", "update",
            "新增", "删除", "重命名", "重构", "修复", "优化", "替换", "改成", "改为", "修改");

    /** RUN_COMMAND.action 允许值。case-insensitive 比较。 */
    private static final Set<String> ACTIONS = Set.of(
            "build", "test", "run", "commit", "push", "pull", "merge", "install",
            "clean", "package", "compile", "exec", "deploy",
            "跑", "构建", "编译", "打包", "测试", "提交", "推送", "拉取", "合并");

    /** WRITE_PROJECT 必填且需结构性校验的槽位。 */
    private static final List<String> WRITE_REQUIRED = List.of("target_file", "change_type");

    /** RUN_COMMAND 必填且需结构性校验的槽位。 */
    private static final List<String> RUN_REQUIRED = List.of("action");

    /**
     * 对给定 label + slots 做结构性校验。
     *
     * @param label 主意图标签(null 视为校验失败)
     * @param slots LLM 抽出的槽位 Map(可为 null)
     * @return 校验结果,包含是否通过、缺失项、非法项
     */
    public ValidationResult validate(IntentLabel label, Map<String, Object> slots) {
        if (label == null) {
            return new ValidationResult(false, List.of("(null)"), List.of());
        }
        Map<String, Object> s = slots == null ? Map.of() : slots;

        List<String> required = requiredSlotsFor(label);
        if (required.isEmpty()) {
            // CHAT_QA / OFF_TOPIC 无必填
            return new ValidationResult(true, List.of(), List.of());
        }

        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();

        for (String key : required) {
            Object v = s.get(key);
            if (v == null || (v instanceof String str && str.isBlank())) {
                missing.add(key);
                continue;
            }
            // 已存在,做结构性校验
            String valueStr = v.toString().trim();
            if (!structuralCheck(label, key, valueStr)) {
                invalid.add(key);
            }
        }

        return new ValidationResult(missing.isEmpty() && invalid.isEmpty(),
                List.copyOf(missing), List.copyOf(invalid));
    }

    /** 返回该 label 必填槽位列表(供观测/debug 用,主流程不需要)。 */
    public List<String> requiredSlotsFor(IntentLabel label) {
        if (label == null) return List.of();
        return switch (label) {
            case WRITE_PROJECT -> WRITE_REQUIRED;
            case RUN_COMMAND   -> RUN_REQUIRED;
            // READ_CODE 的 target 可空,所以这里返回空列表
            // PLANNING 的 goal 可空,同理
            default -> List.of();
        };
    }

    /** 单个槽位的结构性校验。 */
    private boolean structuralCheck(IntentLabel label, String key, String value) {
        return switch (key) {
            case "target_file" -> FILE_PATTERNS.matcher(value).find();
            case "change_type" -> containsIgnoreCase(CHANGE_TYPES, value);
            case "action"      -> containsIgnoreCase(ACTIONS, value);
            case "target"      -> !value.isBlank();
            case "goal"        -> !value.isBlank();
            default            -> true; // 未知 key 视为通过
        };
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        for (String s : set) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    /**
     * 校验结果。
     *
     * @param allPassed     是否所有必填槽位都存在且结构性合法
     * @param missingSlots  缺失的必填槽位 key 列表(在 slots Map 中找不到)
     * @param invalidSlots  存在但结构性校验失败的槽位 key 列表
     */
    public record ValidationResult(
            boolean allPassed,
            List<String> missingSlots,
            List<String> invalidSlots
    ) {
        /** 便于打印/审计的简短描述,如 {@code "MISSING:target_file"} 或 {@code "PASS"}。 */
        public String describe() {
            if (allPassed) return "PASS";
            StringBuilder sb = new StringBuilder();
            if (!missingSlots.isEmpty()) {
                sb.append("MISSING:").append(String.join(",", missingSlots));
            }
            if (!invalidSlots.isEmpty()) {
                if (sb.length() > 0) sb.append(";");
                sb.append("INVALID:").append(String.join(",", invalidSlots));
            }
            return sb.toString();
        }
    }
}
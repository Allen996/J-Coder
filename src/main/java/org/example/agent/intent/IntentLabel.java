package org.example.agent.intent;

/**
 * 一级意图标签。设计稿 §3.1:六类,首期只覆盖前四类,其余留扩展位。
 *
 * <p>每个标签对应一组"典型工具集合"和"风险面",由 {@link IntentAwareToolSet}
 * 与 {@link SlotCompletenessChecker} 消费。
 */
public enum IntentLabel {

    /** 只读理解代码 —— 典型工具:read_file / grep / list_dir / glob_files。 */
    READ_CODE,

    /** 修改代码或新增代码 —— 典型工具:write_file / edit_file / grep。 */
    WRITE_PROJECT,

    /** 执行命令/构建/测试/版本控制 —— 典型工具:run_shell / git_commit / git_status。 */
    RUN_COMMAND,

    /** 纯问答/解释/学习,不涉及代码 —— 不推荐挂工具。 */
    CHAT_QA,

    /** 多步规划,期望产出 TaskPlan —— 典型工具:read_file / list_dir。 */
    PLANNING,

    /** 与编码无关 / 拒绝 / 测试性输入 —— 兜底,不挂工具。 */
    OFF_TOPIC;

    /**
     * 字符串宽松解析。设计稿要求 LLM 输出是严格 JSON,但宽松匹配能减少噪声:
     * 忽略大小写、空格、下划线变体。
     */
    public static IntentLabel parseLoose(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (IntentLabel v : values()) {
            if (v.name().equals(s)) return v;
        }
        // 兼容常见缩写
        if (s.startsWith("READ")) return READ_CODE;
        if (s.startsWith("WRITE")) return WRITE_PROJECT;
        if (s.startsWith("RUN")) return RUN_COMMAND;
        if (s.startsWith("CHAT")) return CHAT_QA;
        if (s.startsWith("PLAN")) return PLANNING;
        if (s.startsWith("OFF")) return OFF_TOPIC;
        return null;
    }
}
package org.example.agent.intent;

/**
 * 一级意图标签。设计稿 §3.1:六类,首期只覆盖前四类,其余留扩展位。
 *
 * <p><b>第三阶段重构</b>:删除 {@code OFF_TOPIC}。理由是"跑题/拒绝/问候/身份询问"在
 * 标注层面与 {@code CHAT_QA} 的边界过于模糊,LLM 实际分类时倾向把这类输入判 CHAT_QA
 * (raw conf 0.95+),OFF_TOPIC 标签设计是过度细分。决定:所有非编程类输入(label = OFF_TOPIC
 * 的 56 条样本)统一并入 CHAT_QA,通过关键词层 + 响应模板控制产品行为(问候友好回应、
 * 拒绝执行、跑题引导回编程话题)。
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

    /**
     * 纯问答/解释/学习/问候/致谢/身份询问/拒绝执行 —— 不挂编程工具。
     *
     * <p>产品层根据关键词层 sub-pattern 区分响应模板:GREETING → 友好回应;
     * INJECTION → 礼貌拒绝;NEGATION → 等用户明确;非编程 → 引导回编程话题;
     * 解释/解释 → 走 LLM 生成内容。
     */
    CHAT_QA,

    /** 多步规划,期望产出 TaskPlan —— 典型工具:read_file / list_dir。 */
    PLANNING;

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
        // 第三阶段:历史 OFF_TOPIC 输入(可能来自老 prompt 或模型输出)
        // 不抛错,兜底为 CHAT_QA,与 golden 重新标注方向一致
        if (s.startsWith("OFF")) return CHAT_QA;
        return null;
    }
}
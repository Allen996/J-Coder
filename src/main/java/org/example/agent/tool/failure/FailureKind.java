package org.example.agent.tool.failure;

/**
 * 工具调用失败的分类。
 *
 * <p>三种 kind 决定 {@link org.example.agent.tool.gateway.ToolGateway} 的后续动作：
 * <ul>
 *   <li>{@link #TRANSIENT} —— 瞬时失败，RetryPolicy 自动重试</li>
 *   <li>{@link #PARAM} —— 参数错误，不重试，结构化反馈给 LLM</li>
 *   <li>{@link #LOGIC} —— 逻辑/语义错误，不重试，看 tool 是否可逆决定是否回滚</li>
 * </ul>
 *
 * <p>判定规则见 {@link FailureClassifier}。绝不在错误消息字符串上判定 kind —— 字符串匹配
 * 是反模式（i18n / 改一个 log 文案就破）。
 */
public enum FailureKind {
    TRANSIENT,
    PARAM,
    LOGIC
}

package org.example.agent.intent;

import org.springframework.stereotype.Component;

/**
 * 降级策略。设计稿 §7:任何一层失败都不能让 loop 卡住。
 *
 * <ul>
>   <li>L1 JSON 解析失败 → conf=0.0,按 OFF_TOPIC 兜底,跳过 L1 路由,用默认模型 + 默认工具集进入 loop</li>
>   <li>L1 调用超时 → 同上</li>
>   <li>L2 失败 → 默认 ALLOW</li>
>   <li>用户选 0 跳过 → 直接进 loop</li>
>   <li>配置开关 cli.intent.enabled=false → 整套跳过</li>
> </ul>
 *
 * <p><b>方案 B 调整</b>:fallback 时 {@code confidence=0.0}(不是 0.5)。
 * 原因是 LLM 调用失败 != 输入是 OFF_TOPIC;前者在评测里属于"未决策",
 * 后者才属于"决策错"。让 conf=0.0 让 tier 自然落到 CLARIFY(< 0.60),
 * 并在评测器里作为"无决策"被排除(见 IntentGoldenSetEvalTest.aggregate)。
 *
 * <p>label 仍保持 {@code defaultLabel}(默认 OFF_TOPIC),这样下游
 * {@link IntentPrompter} / {@link IntentGate} 走 primary 路径不会 NPE。
 */
@Component
public class IntentFallbackPolicy {

    /**
     * Fallback 时的 conf 标记。0.0 = 评测器视为"未决策"不计入 top-1。
     * 历史值 0.5 会被 CLARIFY 阈值 (0.60) 排除,但算 OFF_TOPIC 的 true positive,
     * 反而拉低正确率。
     */
    public static final double FALLBACK_CONFIDENCE = 0.0;

    private final CliIntentProperties properties;

    public IntentFallbackPolicy(CliIntentProperties properties) {
        this.properties = properties;
    }

    public boolean globallyEnabled() {
        return properties.enabled();
    }

    public boolean l1Enabled() {
        return globallyEnabled() && properties.l1().enabled();
    }

    public boolean l2Enabled() {
        return globallyEnabled() && properties.l2().enabled();
    }

    /**
     * 把"分类失败"的 Scored 包成 L1IntentResult,标志 fallback=true。
     * 直接进 loop,不弹任何 UI。
     */
    public L1IntentResult buildFallback(String executionId, String reason) {
        IntentLabel def = properties.fallback().defaultLabel();
        return new L1IntentResult(
                executionId,
                def,
                FALLBACK_CONFIDENCE,
                java.util.List.of(new L1IntentResult.Candidate(def, FALLBACK_CONFIDENCE)),
                java.util.Map.of(),
                java.util.List.of(),
                ModelRouteHint.GENERAL,
                true,
                reason);
    }

    public IntentLabel defaultLabel() {
        return properties.fallback().defaultLabel();
    }

    public CliIntentProperties properties() {
        return properties;
    }
}
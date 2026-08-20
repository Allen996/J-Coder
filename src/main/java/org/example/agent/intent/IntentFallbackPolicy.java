package org.example.agent.intent;

import org.springframework.stereotype.Component;

/**
 * 降级策略。设计稿 §7:任何一层失败都不能让 loop 卡住。
 *
 * <ul>
 *   <li>L1 JSON 解析失败 → conf=0.5,按 OFF_TOPIC 兜底,跳过 L1 路由,用默认模型 + 默认工具集进入 loop</li>
 *   <li>L1 调用超时 → 同上</li>
 *   <li>L2 失败 → 默认 ALLOW</li>
 *   <li>用户选 0 跳过 → 直接进 loop</li>
 *   <li>配置开关 cli.intent.enabled=false → 整套跳过</li>
 * </ul>
 */
@Component
public class IntentFallbackPolicy {

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
                0.5,
                java.util.List.of(new L1IntentResult.Candidate(def, 0.5)),
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
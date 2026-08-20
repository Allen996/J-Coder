package org.example.agent.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 意图识别模块的可调参数。集中在 application.yml 的 {@code cli.intent} 节点下。
 *
 * <p>默认值通过 compact 构造器提供,yml 不写也能跑(取默认值)。
 */
@ConfigurationProperties(prefix = "cli.intent")
public record CliIntentProperties(
        boolean enabled,
        L1 l1,
        L2 l2,
        ModelRouting modelRouting,
        Fallback fallback
) {

    public CliIntentProperties {
        if (l1 == null) l1 = new L1(true, 3000L, new L1Thresholds(0.85, 0.60, 0.15), List.of());
        if (l2 == null) l2 = new L2(true, 2000L, new L2Thresholds(0.80, 0.55, 0.15, 0.55));
        if (modelRouting == null) modelRouting = new ModelRouting("qwen3.7-flash", "qwen3.7-plus", "qwen3.7-plus");
        if (fallback == null) fallback = new Fallback(true, IntentLabel.OFF_TOPIC);
    }

    public CliIntentProperties() {
        this(true, null, null, null, null);
    }

    public record L1(
            boolean enabled,
            long timeoutMs,
            L1Thresholds thresholds,
            List<String> allowLabels
    ) {
        public L1 {
            if (timeoutMs <= 0) timeoutMs = 3000L;
            if (thresholds == null) thresholds = new L1Thresholds(0.85, 0.60, 0.15);
            if (allowLabels == null) allowLabels = List.of();
        }
    }

    public record L1Thresholds(double direct, double offer, double rewriteCap) {
        public L1Thresholds {
            if (direct <= 0) direct = 0.85;
            if (offer <= 0) offer = 0.60;
            if (rewriteCap <= 0) rewriteCap = 0.15;
            if (offer > direct) offer = direct;
        }
    }

    public record L2(
            boolean enabled,
            long timeoutMs,
            L2Thresholds thresholds
    ) {
        public L2 {
            if (timeoutMs <= 0) timeoutMs = 2000L;
            if (thresholds == null) thresholds = new L2Thresholds(0.80, 0.55, 0.15, 0.55);
        }
    }

    public record L2Thresholds(double allow, double warn, double blockConflictGap, double blockFloor) {
        public L2Thresholds {
            if (allow <= 0) allow = 0.80;
            if (warn <= 0) warn = 0.55;
            if (blockConflictGap <= 0) blockConflictGap = 0.15;
            if (blockFloor <= 0) blockFloor = 0.55;
            if (warn > allow) warn = allow;
        }
    }

    public record ModelRouting(String light, String code, String general) {
        public ModelRouting {
            if (light == null || light.isBlank()) light = "qwen3.7-flash";
            if (code == null || code.isBlank()) code = "qwen3.7-plus";
            if (general == null || general.isBlank()) general = "qwen3.7-plus";
        }
    }

    public record Fallback(boolean enabled, IntentLabel defaultLabel) {
        public Fallback {
            if (defaultLabel == null) defaultLabel = IntentLabel.OFF_TOPIC;
        }
    }
}
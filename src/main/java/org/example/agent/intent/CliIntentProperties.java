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
        if (l1 == null) l1 = new L1(true, 3000L, new L1Thresholds(0.85, 0.60, 0.15),
                List.of(), null, null);
        if (l2 == null) l2 = new L2(true, 2000L, new L2Thresholds(0.80, 0.55, 0.15, 0.55));
        if (modelRouting == null) modelRouting = new ModelRouting("qwen3.7-flash", "qwen3.7-plus", "qwen3.7-plus");
        if (fallback == null) fallback = new Fallback(true, IntentLabel.CHAT_QA);
    }

    public CliIntentProperties() {
        this(true, null, null, null, null);
    }

    public record L1(
            boolean enabled,
            long timeoutMs,
            L1Thresholds thresholds,
            List<String> allowLabels,
            Scoring scoring,
            LlmConfidenceCalibrator.Calibration calibration
    ) {
        public L1 {
            if (timeoutMs <= 0) timeoutMs = 3000L;
            if (thresholds == null) thresholds = new L1Thresholds(0.85, 0.60, 0.15);
            if (allowLabels == null) allowLabels = List.of();
            if (scoring == null) scoring = new Scoring(0.6, 0.2, 0.2, 0.15, 0.10);
            if (calibration == null) calibration = new LlmConfidenceCalibrator.Calibration(
                    true, 0.85, 0.40, 0.25, 0.70, 0.60, 0.05);
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

    /**
     * 可调打分权重(对应 design/intent.md §5.3)。默认值与文档一致,
     * 通过 application.yml 的 {@code cli.intent.l1.scoring} 节点调参。
     *
     * <p>首期交付的是关键词词典 + 槽位硬约束的版本,后续可以引入更细的 per-keyword
     * 权重,但先把 5 个标量做成可观测、可灰度的形式,所有调整必须可回滚。
     */
    public record Scoring(
            double wLlm,
            double wKeyword,
            double wSlot,
            double penConflict,
            double penNegative
    ) {
        public Scoring {
            // 默认值与 LocalIntentScorer 历史常量保持一致;
            // 任何调整由 golden.jsonl 的 train 上 grid-search 决定,
            // 用 dev 选超参,只在 test 上报一次。
            if (wLlm <= 0) wLlm = 0.6;
            if (wKeyword <= 0) wKeyword = 0.2;
            if (wSlot <= 0) wSlot = 0.2;
            if (penConflict <= 0) penConflict = 0.15;
            if (penNegative <= 0) penNegative = 0.10;
            // 加性归一化:wLlm + wKeyword + wSlot 必须 = 1.0,在无扣分时 final_conf 自然落 [0,1]。
            // 这里不强制 clamp,因为调参时可能故意偏离,调用方负责。
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
            // 第三阶段:历史 defaultLabel=OFF_TOPIC;OFF_TOPIC 类别删除后,兜底改为 CHAT_QA
            if (defaultLabel == null) defaultLabel = IntentLabel.CHAT_QA;
        }
    }
}
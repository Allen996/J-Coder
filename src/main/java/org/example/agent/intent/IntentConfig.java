package org.example.agent.intent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 意图识别模块的 bean 装配。
 *
 * <p>本包下大部分组件({@link IntentGate} / {@link LocalIntentScorer} /
 * {@link KeywordSignalExtractor} 等)都用 {@code @Component} 标注,Spring
 * 启动时会自动扫描并注册。本配置只放那些不带 Spring 注解、必须显式注册的
 * 无状态 POJO / 策略对象,目的是:
 *
 * <ol>
 *   <li>让 {@link IntentGate} 的构造函数解析不再因 {@code SlotCompletenessValidator}
 *       / {@code LlmConfidenceCalibrator} 找不到 bean 而失败 —— 历史实现里
 *       {@code IntentGate} 构造器虽然做了 {@code null} 兜底,但 Spring 在
 *       实例化之前的类型解析阶段就需要这两个 bean,兜底逻辑不会生效,启动
 *       直接抛 {@code UnsatisfiedDependencyException}。</li>
 *   <li>让 {@code application.yml} 中 {@code cli.intent.l1.calibration} 的
 *       阈值真正流入到 {@link LlmConfidenceCalibrator};否则在校准器被
 *       手工 new 时,阈值只能走 record compact-constructor 的默认值,
 *       yml 调参不生效。</li>
 * </ol>
 */
@Configuration
public class IntentConfig {

    /**
     * 槽位结构性校验器。无状态 POJO,共享单例即可。
     */
    @Bean
    public SlotCompletenessValidator slotCompletenessValidator() {
        return new SlotCompletenessValidator();
    }

    /**
     * LLM 自评置信度校准器,使用 {@link CliIntentProperties} 中
     * {@code l1.calibration} 节点的阈值;若 yml 未配置则走
     * {@link LlmConfidenceCalibrator.Calibration} compact-constructor 的默认值。
     */
    @Bean
    public LlmConfidenceCalibrator llmConfidenceCalibrator(CliIntentProperties properties) {
        LlmConfidenceCalibrator.Calibration cfg =
                properties == null || properties.l1() == null
                        ? new LlmConfidenceCalibrator.Calibration(
                                true, 0.85, 0.40, 0.25, 0.70, 0.60, 0.05)
                        : properties.l1().calibration();
        return new LlmConfidenceCalibrator(cfg);
    }
}

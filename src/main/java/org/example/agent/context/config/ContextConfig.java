package org.example.agent.context.config;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文模块的 bean 装配（part3.md 两层上下文 + part4.md 三层记忆）。
 *
 * <p>所有上下文 / 记忆相关 store 已经用 {@code @Component} 标注，
 * Spring 启动时会自动扫描并在 bean 工厂中发布。本类只放那些需要显式注册的
 * 值对象 / 策略 bean。
 */
@Configuration
public class ContextConfig {

    /**
     * {@link ContextBudgetPolicy} bean —— 通过 {@code agent.context.*} 节点覆盖默认值。
     * 兼容：未配置任何 {@code agent.context.*} 时仍用 {@link ContextBudgetPolicy#defaultPolicy()}。
     */
    @Bean
    public ContextBudgetPolicy contextBudgetPolicy(
            @Value("${agent.context.compression-threshold:0.8}") double compressionThreshold,
            @Value("${agent.context.context-window-max:128000}") long contextWindowMax,
            @Value("${agent.context.static-reserved:4000}") long staticReserved,
            @Value("${agent.context.memory-token-reservation:4096}") long memoryTokenReservation,
            @Value("${agent.context.max-single-call-completion:4096}") long maxSingleCallCompletion,
            @Value("${agent.context.keep-recent-rounds:5}") int keepRecentRounds) {
        return ContextBudgetPolicy.builder()
                .compressionThreshold(compressionThreshold)
                .contextWindowMax(contextWindowMax)
                .staticReserved(staticReserved)
                .memoryTokenReservation(memoryTokenReservation)
                .maxSingleCallCompletion(maxSingleCallCompletion)
                .keepRecentRounds(keepRecentRounds)
                .build();
    }

    /**
     * 长期记忆默认路径由 {@code agent.project-root} 配置；
     * 没有显式覆盖时使用项目根路径。
     */
    @Bean
    public LongTermStore longTermStore() {
        String root = System.getProperty("agent.project-root", "");
        return new LongTermStore(root);
    }

    @Bean
    public MemoryIndex memoryIndex() {
        String root = System.getProperty("agent.project-root", "");
        return new MemoryIndex(root);
    }
}

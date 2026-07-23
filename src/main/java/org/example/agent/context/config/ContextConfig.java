package org.example.agent.context.config;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 上下文模块的 bean 装配（part3.md 三层上下文）。
 *
 * <p>{@link ContextBudgetPolicy} 是一个 {@code @Builder} 值对象，不带 {@code @Component}，
 * 但被 ContextBuilder / CompactCommand / ContextCommand / AutoCompressionObserver /
 * ContextAwareAgentBudgetFactory / ConversationCompressor 以构造注入方式依赖。
 * 这里把它注册成单例 bean，让上述组件的构造注入能被 Spring 解析（否则 IDE / 启动期都会
 * 报 “No beans of type ContextBudgetPolicy found”）。
 */
@Configuration
public class ContextConfig {

    @Bean
    public ContextBudgetPolicy contextBudgetPolicy() {
        return ContextBudgetPolicy.defaultPolicy();
    }
}

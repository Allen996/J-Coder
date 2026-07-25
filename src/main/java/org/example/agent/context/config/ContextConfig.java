package org.example.agent.context.config;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.memory.MidTermStore;
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

    @Bean
    public ContextBudgetPolicy contextBudgetPolicy() {
        return ContextBudgetPolicy.defaultPolicy();
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

    @Bean
    public MidTermStore midTermStore() {
        return new MidTermStore();
    }
}

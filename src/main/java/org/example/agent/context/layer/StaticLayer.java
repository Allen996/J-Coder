package org.example.agent.context.layer;

import jakarta.annotation.PostConstruct;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.builder.ContextBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 静态层（part3.md §6.2）。
 *
 * <p>承载每次会话基本不变的内容，按 key 组织：
 * <ul>
 *   <li>{@code role_definition} — 智能助手角色描述</li>
 *   <li>{@code tool_list} — 可用工具列表（结构化通道，由 Spring AI toolCallbacks 承载）</li>
 *   <li>{@code code_writing_cot} — 写代码的完整思维链</li>
 *   <li>{@code runtime_meta} — 当前时间、模型名、项目根路径</li>
 * </ul>
 *
 * <p>加载时机：{@code @PostConstruct} 即写入默认 4 个 key（不依赖 AgentTask 的部分），
 * Runtime 启动时由 {@link ContextBuilder#loadStaticLayer(org.example.agent.core.task.AgentTask)}
 * 重新渲染（特别是 runtime_meta / tool_list 会随任务变化）。
 * {@code /load} 触发按 key 独立刷新。
 */
@Component
public class StaticLayer extends ContextLayer {

    private volatile Instant lastLoadedAt;

    public StaticLayer() {
        for (ContextKey k : declaredKeys()) {
            put(k, ContextEntry.empty(k));
        }
    }

    @PostConstruct
    public void initDefaults() {
        // 4 个 key 各自的默认内容（不依赖 AgentTask；Runtime 启动后会重新渲染）
        put(ContextKey.ROLE_DEFINITION, ContextEntry.builder()
                .key(ContextKey.ROLE_DEFINITION)
                .text(ContextBuilder.renderRoleDefinition())
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(ContextBuilder.renderRoleDefinition()))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://role_definition")
                .build());
        put(ContextKey.CODE_WRITING_COT, ContextEntry.builder()
                .key(ContextKey.CODE_WRITING_COT)
                .text(ContextBuilder.renderCodeWritingCot())
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(ContextBuilder.renderCodeWritingCot()))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://code_writing_cot")
                .build());
        put(ContextKey.RUNTIME_META, ContextEntry.builder()
                .key(ContextKey.RUNTIME_META)
                .text(ContextBuilder.renderRuntimeMeta(null))
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(ContextBuilder.renderRuntimeMeta(null)))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://runtime_meta")
                .build());
        put(ContextKey.TOOL_LIST, ContextEntry.builder()
                .key(ContextKey.TOOL_LIST)
                .text(null)
                .estimatedTokens(0L)
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://tool_list")
                .build());
        lastLoadedAt = Instant.now();
    }

    @Override
    public String layerName() {
        return "static";
    }

    @Override
    public ContextKey[] declaredKeys() {
        return new ContextKey[] {
                ContextKey.ROLE_DEFINITION,
                ContextKey.TOOL_LIST,
                ContextKey.CODE_WRITING_COT,
                ContextKey.RUNTIME_META
        };
    }

    /** 在 4 个 key 全部装配后调用，给 /load 触发刷新留时间戳。 */
    public void markLoaded() {
        this.lastLoadedAt = Instant.now();
    }

    public Instant lastLoadedAt() {
        return lastLoadedAt;
    }
}

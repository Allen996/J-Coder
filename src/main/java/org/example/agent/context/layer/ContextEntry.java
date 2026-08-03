package org.example.agent.context.layer;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 单个 context key 的内容快照（part3.md §6.1 字典结构）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code text}：序列化通道为 TEXT 的 key 的最终字符串；STRUCTURED 通道的 key 这里为 null。</li>
 *   <li>{@code structuredPayload}：序列化通道为 STRUCTURED 的 key 的载体
 *       （例如 {@code tool_list} 承载 ToolCallback 列表；messages 承载 Spring AI Message 列表）。
 *       TEXT 通道为 null。</li>
 *   <li>{@code estimatedTokens}：自上次刷新起的估算 token；估算口径与 {@code ContextBudgetPolicy.estimateTextTokens} 一致。</li>
 *   <li>{@code lastRefreshedAt}：上次写入时间；用于 /load 触发按 key 独立判断是否需要重载。</li>
 *   <li>{@code sourceRef}：来源描述（如 {@code "system://default-role"} / {@code "memory://Nico.md"} / {@code "session://{sessionId}"}），
 *       便于 /context 可观测输出。</li>
 * </ul>
 *
 * <p>不可变：每次 key 内容更新产生一个新 entry，调用方按引用替换。
 */
@Getter
@Builder
@ToString(of = {"key", "estimatedTokens", "sourceRef", "lastRefreshedAt"})
public final class ContextEntry {

    private final ContextKey key;
    private final String text;
    private final Object structuredPayload;
    private final long estimatedTokens;
    private final Instant lastRefreshedAt;
    private final String sourceRef;

    public static ContextEntry empty(ContextKey key) {
        return ContextEntry.builder()
                .key(key)
                .text(null)
                .structuredPayload(null)
                .estimatedTokens(0L)
                .lastRefreshedAt(Instant.now())
                .sourceRef("(empty)")
                .build();
    }
}

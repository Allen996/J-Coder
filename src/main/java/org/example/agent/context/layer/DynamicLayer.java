package org.example.agent.context.layer;

import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 动态层（part3.md §6.3）。
 *
 * <p>承载运行时累积的内容，按 key 组织：
 * <ul>
 *   <li>{@code messages} — 短期记忆原始流（最近 5 轮递减 + 单轮 LLM 压缩）</li>
 *   <li>{@code mid_term} — 中期记忆（Session 整体总结）</li>
 *   <li>{@code long_term} — 长期记忆（项目骨架 Nico.md）</li>
 *   <li>{@code memory_index} — 记忆索引（MEMORY.md，LRU 20，常驻不被压缩）</li>
 *   <li>{@code ephemeral} — 临时观察事件流（每个 step 重建清理）</li>
 * </ul>
 *
 * <p>每个 key 完全独立：
 * <ul>
 *   <li>{@code memory_index} 永不压缩。</li>
 *   <li>{@code mid_term} 重新生成会整体替换。</li>
 *   <li>{@code long_term} 仅在用户确认候选条目后追加。</li>
 *   <li>{@code messages} 由 5 轮递减 + 单轮 LLM 压缩控制。</li>
 *   <li>{@code ephemeral} 每个 step 结束清空。</li>
 * </ul>
 */
@Component
public class DynamicLayer extends ContextLayer {

    private volatile Instant lastEphemeralResetAt;

    public DynamicLayer() {
        for (ContextKey k : declaredKeys()) {
            put(k, ContextEntry.empty(k));
        }
    }

    @Override
    public String layerName() {
        return "dynamic";
    }

    @Override
    public ContextKey[] declaredKeys() {
        return new ContextKey[] {
                ContextKey.MESSAGES,
                ContextKey.MID_TERM,
                ContextKey.LONG_TERM,
                ContextKey.MEMORY_INDEX,
                ContextKey.EPHEMERAL
        };
    }

    /** 每 step 结束清空 ephemeral key；其它 key 不动。 */
    public void resetEphemeral() {
        put(ContextKey.EPHEMERAL, ContextEntry.empty(ContextKey.EPHEMERAL));
        this.lastEphemeralResetAt = Instant.now();
    }

    public Instant lastEphemeralResetAt() {
        return lastEphemeralResetAt;
    }
}

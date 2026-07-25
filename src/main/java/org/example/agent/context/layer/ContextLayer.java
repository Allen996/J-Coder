package org.example.agent.context.layer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 上下文层抽象（part3.md §6.1 两层上下文）。
 *
 * <p>两层（Static / Dynamic）共享同一份接口：
 * 每个 key 独立存储，独立 put / get / replace，修改一个 key 不触发其他 key 的重渲染。
 *
 * <p>实现：
 * <ul>
 *   <li>{@link StaticLayer} — 启动 + /load 时刷新；整体按只读对待，特定 key 可在显式触发下重写。</li>
 *   <li>{@link DynamicLayer} — 运行时累积；每个 key 有独立的修改策略。</li>
 * </ul>
 *
 * <p>为什么用 {@code LinkedHashMap}：保留 key 插入顺序，便于渲染时输出稳定。
 */
public abstract class ContextLayer {

    private final Map<ContextKey, ContextEntry> entries = new LinkedHashMap<>();

    /** 放一个 key（替换式）。null entry 等同于 remove。 */
    public ContextLayer put(ContextKey key, ContextEntry entry) {
        if (entry == null) {
            entries.remove(key);
        } else {
            entries.put(key, entry);
        }
        return this;
    }

    public Optional<ContextEntry> get(ContextKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    public ContextEntry require(ContextKey key) {
        ContextEntry e = entries.get(key);
        if (e == null) {
            throw new IllegalStateException("ContextLayer " + layerName() + " missing key: " + key.wireName());
        }
        return e;
    }

    public boolean contains(ContextKey key) {
        return entries.containsKey(key);
    }

    public Collection<ContextEntry> entries() {
        return entries.values();
    }

    public Map<ContextKey, ContextEntry> asMap() {
        return java.util.Collections.unmodifiableMap(entries);
    }

    /** 该层所有 key 的 token 估算总和。 */
    public long totalEstimatedTokens() {
        long t = 0L;
        for (ContextEntry e : entries.values()) {
            t += e.getEstimatedTokens();
        }
        return t;
    }

    /** 抽象层名（"static" / "dynamic"）。 */
    public abstract String layerName();

    /** 该层期望持有的所有 key（按 part3.md §6.2 / 6.3 列出）。 */
    public abstract ContextKey[] declaredKeys();
}

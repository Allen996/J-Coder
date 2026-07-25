package org.example.agent.context.layer;

/**
 * 上下文键字典（part3.md §6.1 两层上下文）。
 *
 * <p>每层内部按 key 组织，每个 key 独立可改 / 独立可刷 / 独立可裁。
 * key 决定序列化通道：文本类进 SystemMessage 文本段，结构化类（tool schema）走 Spring AI 工具回调。
 *
 * <p>Static Layer 4 个 key：
 * <ul>
 *   <li>{@link #ROLE_DEFINITION} — 智能助手角色描述</li>
 *   <li>{@link #TOOL_LIST} — 可用工具列表（Spring AI toolCallbacks 结构化通道，不占文本预算）</li>
 *   <li>{@link #CODE_WRITING_COT} — 写代码的完整思维链</li>
 *   <li>{@link #RUNTIME_META} — 当前时间、模型名、项目根路径</li>
 * </ul>
 *
 * <p>Dynamic Layer 5 个 key：
 * <ul>
 *   <li>{@link #MESSAGES} — 短期记忆原始流（最近 5 轮递减 + 单轮 LLM 压缩）</li>
 *   <li>{@link #MID_TERM} — 中期记忆（Session 整体总结）</li>
 *   <li>{@link #LONG_TERM} — 长期记忆（项目骨架 Nico.md）</li>
 *   <li>{@link #MEMORY_INDEX} — 记忆索引（MEMORY.md，LRU 20，常驻不被压缩）</li>
 *   <li>{@link #EPHEMERAL} — 临时观察事件流（每个 step 重建清理）</li>
 * </ul>
 */
public enum ContextKey {

    // ---- Static Layer ----
    ROLE_DEFINITION(Layer.STATIC, Channel.TEXT, "role_definition"),
    TOOL_LIST(Layer.STATIC, Channel.STRUCTURED, "tool_list"),
    CODE_WRITING_COT(Layer.STATIC, Channel.TEXT, "code_writing_cot"),
    RUNTIME_META(Layer.STATIC, Channel.TEXT, "runtime_meta"),

    // ---- Dynamic Layer ----
    MESSAGES(Layer.DYNAMIC, Channel.TEXT, "messages"),
    MID_TERM(Layer.DYNAMIC, Channel.TEXT, "mid_term"),
    LONG_TERM(Layer.DYNAMIC, Channel.TEXT, "long_term"),
    MEMORY_INDEX(Layer.DYNAMIC, Channel.TEXT, "memory_index"),
    EPHEMERAL(Layer.DYNAMIC, Channel.TEXT, "ephemeral");

    public enum Layer { STATIC, DYNAMIC }

    /** 序列化通道：文本 → SystemMessage 文本段；结构化 → Spring AI 专用选项（如 toolCallbacks）。 */
    public enum Channel { TEXT, STRUCTURED }

    private final Layer layer;
    private final Channel channel;
    private final String wireName;

    ContextKey(Layer layer, Channel channel, String wireName) {
        this.layer = layer;
        this.channel = channel;
        this.wireName = wireName;
    }

    public Layer layer() { return layer; }
    public Channel channel() { return channel; }
    public String wireName() { return wireName; }

    public boolean isDynamic() { return layer == Layer.DYNAMIC; }
    public boolean isStatic() { return layer == Layer.STATIC; }
    public boolean isTextual() { return channel == Channel.TEXT; }
}

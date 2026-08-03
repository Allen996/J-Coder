package org.example.agent.context.budget;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.budget.AgentBudget;

/**
 * 两层上下文的 token 预算切片（part3.md §6.5）。
 *
 * <p>字段映射 part3.md 表格：
 * <ul>
 *   <li>{@code contextWindowMax}        = 128000（按模型动态调整）</li>
 *   <li>{@code staticReserved}          = 4000（静态层整体）</li>
 *   <li>{@code dynamicReserved}         = 剩余（动态层整体）</li>
 *   <li>{@code memoryTokenReservation}  = 4096（completion 预留）</li>
 *   <li>{@code maxSingleCallCompletion} = 4096（单次最大输出）</li>
 * </ul>
 *
 * <p>动态层内部按 key 软配额（part3.md §6.5 表格下方）：
 * <ul>
 *   <li>{@code messagesQuota}     — 不设硬配额，由「最近 5 轮递减 + 单轮 LLM 压缩」控制</li>
 *   <li>{@code midTermQuota}      = 1024（1K）；超限触发整体重新生成</li>
 *   <li>{@code longTermQuota}     = 2048（2K）；超限触发按重要性重排</li>
 *   <li>{@code memoryIndexQuota}  = 512（0.5K），LRU 20 项，常驻不被压缩</li>
 *   <li>{@code ephemeralStepBudget} = 2048（2K/step）硬上限；超额截断最旧观察</li>
 * </ul>
 *
 * <p>派生方法 {@link #dynamicReserved()}：从 contextWindowMax 扣掉 staticReserved / memoryTokenReservation /
 * maxSingleCallCompletion 之后剩下的预算，专供动态层使用。
 *
 * <p>派生方法 {@link #shouldTriggerCompression()}：阈值 0.8，与 part3.md "sessionUsed / sessionReserved
 * &gt; 0.8 时触发 ContextCompressionHook" 一致。
 */
@Getter
@Builder
@ToString
public final class ContextBudgetPolicy {

    public static final long DEFAULT_CONTEXT_WINDOW_MAX = 128_000L;
    public static final long DEFAULT_STATIC_RESERVED = 4_000L;
    public static final long DEFAULT_MEMORY_RESERVATION = 4_096L;
    public static final long DEFAULT_MAX_SINGLE_CALL_COMPLETION = 4_096L;
    public static final int  DEFAULT_KEEP_RECENT_ROUNDS = 5;
    public static final long DEFAULT_SUMMARY_TOKEN_CAP = 1_500L;
    public static final double DEFAULT_COMPRESSION_THRESHOLD = 0.8;

    public static final long DEFAULT_MID_TERM_QUOTA = 1_024L;
    public static final long DEFAULT_LONG_TERM_QUOTA = 2_048L;
    public static final long DEFAULT_MEMORY_INDEX_QUOTA = 512L;
    public static final long DEFAULT_EPHEMERAL_STEP_BUDGET = 2_048L;
    public static final int  DEFAULT_MID_TERM_TOP_N = 5;
    public static final int  DEFAULT_MEMORY_INDEX_LRU = 20;

    /** 整个上下文窗口的最大 token 数。0 表示禁用 per-call 预算。 */
    @Builder.Default
    private final long contextWindowMax = DEFAULT_CONTEXT_WINDOW_MAX;

    /** Static Layer 固定开销（含 tools schema）保留 token。 */
    @Builder.Default
    private final long staticReserved = DEFAULT_STATIC_RESERVED;

    /** 记忆系统预留的 token。 */
    @Builder.Default
    private final long memoryTokenReservation = DEFAULT_MEMORY_RESERVATION;

    /** 单次响应预留的 completion 阈值（OpenAI 兼容协议的 max_tokens）。 */
    @Builder.Default
    private final long maxSingleCallCompletion = DEFAULT_MAX_SINGLE_CALL_COMPLETION;

    /** 摘要上限 token（part3.md §6.5 规则 4）。 */
    @Builder.Default
    private final long summaryTokenCap = DEFAULT_SUMMARY_TOKEN_CAP;

    /** 触发自动压缩的动态层占用比。 */
    @Builder.Default
    private final double compressionThreshold = DEFAULT_COMPRESSION_THRESHOLD;

    /** 短期记忆加载轮数（part3.md §6.5 规则 1）。默认 5。 */
    @Builder.Default
    private final int keepRecentRounds = DEFAULT_KEEP_RECENT_ROUNDS;

    /** mid_term 单次加载软配额。 */
    @Builder.Default
    private final long midTermQuota = DEFAULT_MID_TERM_QUOTA;

    /** long_term 单次加载软配额。 */
    @Builder.Default
    private final long longTermQuota = DEFAULT_LONG_TERM_QUOTA;

    /** memory_index 单次加载软配额。 */
    @Builder.Default
    private final long memoryIndexQuota = DEFAULT_MEMORY_INDEX_QUOTA;

    /** ephemeral 单 step 硬上限。 */
    @Builder.Default
    private final long ephemeralStepBudget = DEFAULT_EPHEMERAL_STEP_BUDGET;

    /** mid_term 隐式匹配返回最相关 N 条。 */
    @Builder.Default
    private final int midTermTopN = DEFAULT_MID_TERM_TOP_N;

    /** memory_index LRU 容量。 */
    @Builder.Default
    private final int memoryIndexLru = DEFAULT_MEMORY_INDEX_LRU;

    public static ContextBudgetPolicy defaultPolicy() {
        return ContextBudgetPolicy.builder().build();
    }

    /** 动态层可用预算（contextWindowMax - staticReserved - memoryTokenReservation - maxSingleCallCompletion）。 */
    public long dynamicReserved() {
        long reserved = contextWindowMax - staticReserved
                - memoryTokenReservation - maxSingleCallCompletion;
        return Math.max(0L, reserved);
    }

    /** 静态层实际预算。 */
    public long staticReserved() {
        return staticReserved;
    }

    /** 触发自动压缩阈值。 */
    public boolean shouldTriggerCompression(long dynamicUsed) {
        long reserved = dynamicReserved();
        if (reserved <= 0) return false;
        double ratio = (double) dynamicUsed / (double) reserved;
        return ratio > compressionThreshold;
    }

    /** 给定当前静态层已用 token，返回剩余预算。 */
    public long remainingStatic(long used) {
        return Math.max(0L, staticReserved - used);
    }

    /** 简易 token 估算：1 token ≈ 4 字符。 */
    public static long estimateTextTokens(String s) {
        if (s == null || s.isEmpty()) return 0L;
        return Math.max(1L, (s.length() + 3) / 4);
    }

    /**
     * 从 {@link AgentBudget} 派生 policy 时使用 —— 既有 AgentBudget 是「执行级硬上限」，
     * 与本类的「静态切片上限」正交。本构造器的语义是：执行级 budget 不参与切片，
     * 切片只与模型能力（contextWindowMax）有关。
     */
    public static AgentBudget toAgentBudget(ContextBudgetPolicy policy) {
        if (policy == null) policy = defaultPolicy();
        return AgentBudget.builder()
                .contextWindowMax(policy.getContextWindowMax())
                .memoryTokenReservation(policy.getMemoryTokenReservation())
                .maxSingleCallCompletion(policy.getMaxSingleCallCompletion())
                .build();
    }
}

package org.example.agent.context.budget;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.context.project.FileTreeNode;
import org.example.agent.context.project.ProjectContext;

/**
 * 三层上下文的 token 预算切片（part3.md §6.4）。
 *
 * <p>字段映射 part3.md 表格：
 * <ul>
 *   <li>{@code contextWindowMax}    = 128000（按模型动态调整）</li>
 *   <li>{@code systemReserved}       = 4000（system + tools schema）</li>
 *   <li>{@code projectReserved}      = 8000（project layer）</li>
 *   <li>{@code memoryTokenReservation} = 4096（completion 预留）</li>
 *   <li>{@code maxSingleCallCompletion} = 4096（单次最大输出）</li>
 *   <li>{@code sessionReserved}      = 剩余（消息历史）</li>
 * </ul>
 *
 * <p>派生方法 {@link #sessionReserved()}：从 contextWindowMax 中扣掉 system / project / memory /
 * completion 之后剩下的预算，专供 session messages 使用。这是 part3.md "装配消息" 步骤 4 的判定基准。
 *
 * <p>派生方法 {@link #shouldTriggerCompression()}：阈值 0.8，与 part3.md "sessionUsed / sessionReserved
 * &gt; 0.8 时触发 ContextCompressionHook" 一致。
 *
 * <p>派生方法 {@link #compressOverheadTokens()}：摘要系统消息的 token 预算（默认 1500），在
 * {@link org.example.agent.context.compression.ConversationCompressor} 中作为截断上限。
 */
@Getter
@Builder
@ToString
public final class ContextBudgetPolicy {

    public static final long DEFAULT_CONTEXT_WINDOW_MAX = 128_000L;
    public static final long DEFAULT_SYSTEM_RESERVED = 4_000L;
    public static final long DEFAULT_PROJECT_RESERVED = 8_000L;
    public static final long DEFAULT_MEMORY_RESERVATION = 4_096L;
    public static final long DEFAULT_MAX_SINGLE_CALL_COMPLETION = 4_096L;
    public static final int  DEFAULT_KEEP_RECENT_ROUNDS = 5;
    public static final long DEFAULT_SUMMARY_TOKEN_CAP = 1_500L;
    public static final double DEFAULT_COMPRESSION_THRESHOLD = 0.8;

    /** 整个上下文窗口的最大 token 数。0 表示禁用 per-call 预算。 */
    @Builder.Default
    private final long contextWindowMax = DEFAULT_CONTEXT_WINDOW_MAX;

    /** System Layer 固定开销（含 tools schema）保留 token。 */
    @Builder.Default
    private final long systemReserved = DEFAULT_SYSTEM_RESERVED;

    /** Project Layer 固定开销保留 token。 */
    @Builder.Default
    private final long projectReserved = DEFAULT_PROJECT_RESERVED;

    /** 记忆系统预留的 token。 */
    @Builder.Default
    private final long memoryTokenReservation = DEFAULT_MEMORY_RESERVATION;

    /** 单次响应预留的 completion 阈值（OpenAI 兼容协议的 max_tokens）。 */
    @Builder.Default
    private final long maxSingleCallCompletion = DEFAULT_MAX_SINGLE_CALL_COMPLETION;

    /** 摘要上限 token（part3.md §6.5 规则 4）。 */
    @Builder.Default
    private final long summaryTokenCap = DEFAULT_SUMMARY_TOKEN_CAP;

    /** 触发自动压缩的 session 占用比。 */
    @Builder.Default
    private final double compressionThreshold = DEFAULT_COMPRESSION_THRESHOLD;

    /** 摘要时保留的最近 K 轮（part3.md §6.5 规则 1）。 */
    @Builder.Default
    private final int keepRecentRounds = DEFAULT_KEEP_RECENT_ROUNDS;

    /**
     * 从 {@link AgentBudget} 派生 policy 时使用 —— 既有 AgentBudget 是「执行级硬上限」，
     * 与本类的「静态切片上限」正交。本构造器的语义是：执行级 budget 不参与切片，
     * 切片只与模型能力（contextWindowMax）有关。
     */
    public static ContextBudgetPolicy defaultPolicy() {
        return ContextBudgetPolicy.builder().build();
    }

    /** 装配时的 session 层可用预算。 */
    public long sessionReserved() {
        long reserved = contextWindowMax - systemReserved - projectReserved
                - memoryTokenReservation - maxSingleCallCompletion;
        return Math.max(0L, reserved);
    }

    /** 装配时的 Project Layer 实际预算（part3.md §6.4 步骤 3）。 */
    public long projectReserved() {
        return projectReserved;
    }

    /** 装配时的 System Layer 实际预算。 */
    public long systemReserved() {
        return systemReserved;
    }

    /** 是否应该触发自动压缩。 */
    public boolean shouldTriggerCompression(long sessionUsed) {
        long reserved = sessionReserved();
        if (reserved <= 0) return false;
        double ratio = (double) sessionUsed / (double) reserved;
        return ratio > compressionThreshold;
    }

    /** 给定当前 system / project 已用 token，返回剩余 system / project 预算（不预扣）。 */
    public long remainingProject(long used) {
        return Math.max(0L, projectReserved - used);
    }
    public long remainingSystem(long used) {
        return Math.max(0L, systemReserved - used);
    }

    /** Project Layer 实际装配 token（基于 ProjectContext 实际内容计算；不调用 LLM，仅字符 / 4 估算）。 */
    public static long estimateProjectTokens(ProjectContext ctx) {
        if (ctx == null) return 0L;
        long total = 0L;
        // 文件树
        total += estimateFileTreeTokens(ctx.getFileTree());
        // CLAUDE.md
        total += estimateTextTokens(ctx.getClaudeMd());
        // README
        total += estimateTextTokens(ctx.getReadme());
        // 关键配置
        if (ctx.getKeyConfigFiles() != null) {
            for (ProjectContext.KeyConfigFile k : ctx.getKeyConfigFiles()) {
                total += 16L; // 路径 + 类型标签
                total += estimateTextTokens(k.getContent());
            }
        }
        return total;
    }

    /** 简易 token 估算：1 token ≈ 4 字符（英文为主；中文 / 代码略偏短，这里统一采用）。 */
    public static long estimateTextTokens(String s) {
        if (s == null || s.isEmpty()) return 0L;
        return Math.max(1L, (s.length() + 3) / 4);
    }

    private static long estimateFileTreeTokens(FileTreeNode node) {
        if (node == null) return 0L;
        long t = estimateTextTokens(node.getName()) + 2L;
        if (node.getChildren() != null) {
            for (FileTreeNode c : node.getChildren()) {
                t += estimateFileTreeTokens(c);
            }
        }
        return t;
    }
}
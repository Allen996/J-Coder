package org.example.agent.core.budget;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;

/**
 * 单次 AgentTask 执行的预算上限。AgentRuntime 在执行过程中向观察者广播累计值，
 * 触发对应的强制结束策略。
 *
 * 与 agent-context.ContextBudgetPolicy 的关系：Policy 是「装配消息时的静态切片上限」，
 * 本类是「单次执行全生命周期的硬上限」。前者影响发往 LLM 的 prompt 大小，
 * 后者保证失控的循环有兜底。
 *
 * 关于「上下文窗口有效空间」：LLM 的 context window 中真正能放对话历史的容量，
 * 需要扣除 long-term memory 占用的 token 和单轮响应预留的 completion 阈值。
 * 派生方法 {@link #getEffectivePerCallPromptBudget()} 给出单次 LLM 调用的可用 prompt 上限，
 * TokenBudgetObserver 用它做 per-call 拦截；压缩模块挂接后，应在溢出时先尝试压缩，
 * 压缩后仍超才终止。
 */
@Getter
@Builder
@ToString
public class AgentBudget {

    /** 单次执行累计 prompt+completion tokens 上限。0 = 不限制。 */
    @Builder.Default
    private final long maxTotalTokens = 0L;

    /** 单次执行最大 ReAct 步数（一次 model 输出 + 一次 tool 调用计 1 步）。默认 12。 */
    @Builder.Default
    private final int maxSteps = 12;

    /** 单次执行总 wall-clock 超时。Duration.ZERO = 不限制。 */
    @Builder.Default
    private final Duration maxWallClock = Duration.ofMinutes(5);

    /** 单次 LLM 调用超时（model.call -> response）。 */
    @Builder.Default
    private final Duration modelCallTimeout = Duration.ofMinutes(2);

    /** 单次工具调用超时（toolGateway.invoke）。 */
    @Builder.Default
    private final Duration toolCallTimeout = Duration.ofSeconds(60);

    /**
     * LLM 的硬上下文窗口上限（模型能看见的总 token 数）。0 = 不做 per-call 检查。
     * 通常由模型能力决定，例如 Qwen-Long = 1_000_000、qwen-plus = 131_072。
     */
    @Builder.Default
    private final long contextWindowMax = 0L;

    /**
     * 长记忆系统每次调用固定占用的 token 预留（system prompt + 持久化记忆）。
     * per-call 有效 prompt = contextWindowMax - memoryTokenReservation - maxSingleCallCompletion。
     */
    @Builder.Default
    private final long memoryTokenReservation = 0L;

    /**
     * 单轮响应预留的 completion 阈值（OpenAI 兼容协议的 max_tokens 参数）。
     * LLM 的输出空间必须从 context window 中提前扣除，否则会让 prompt 端被挤掉。
     */
    @Builder.Default
    private final long maxSingleCallCompletion = 0L;

    /**
     * 单次 LLM 调用实际可用于 prompt 的 token 上限。
     * <p>
     * 推导公式：effective = contextWindowMax - memoryTokenReservation - maxSingleCallCompletion。
     * 当 contextWindowMax <= 0 表示未配置窗口信息，返回 Long.MAX_VALUE 让调用方跳过 per-call 检查。
     *
     * @return 可用 prompt token；未配置窗口时返回 {@link Long#MAX_VALUE}
     */
    public long getEffectivePerCallPromptBudget() {
        if (contextWindowMax <= 0) {
            return Long.MAX_VALUE;
        }
        long effective = contextWindowMax - memoryTokenReservation - maxSingleCallCompletion;
        return Math.max(0L, effective);
    }

    public static AgentBudget defaultChat() {
        return AgentBudget.builder().build();
    }
}

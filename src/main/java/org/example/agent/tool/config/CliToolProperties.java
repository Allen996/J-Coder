package org.example.agent.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 工具系统的可调参数。集中在 application.yml 的 {@code cli.tool} 节点下。
 *
 * <p>默认值通过 compact 构造器提供,所以 application.yml 即使不写也能跑(取默认值)。
 * 想覆盖时在 yml 里写即可。
 */
@ConfigurationProperties(prefix = "cli.tool")
public record CliToolProperties(
        boolean parallel,
        long defaultTimeoutMs,
        int toolExecutorCore,
        int toolExecutorMax,
        ResultCache resultCache,
        Authorization authorization
) {

    public CliToolProperties {
        if (defaultTimeoutMs <= 0) defaultTimeoutMs = 60_000L;
        if (toolExecutorCore < 0) toolExecutorCore = 0;
        if (toolExecutorMax <= 0) toolExecutorMax = 8;
        if (resultCache == null) {
            resultCache = new ResultCache(true, 524_288_000L, Duration.ofDays(7), 16_384);
        }
        if (authorization == null) {
            authorization = new Authorization(Authorization.Mode.PROMPT, List.of());
        }
    }

    public CliToolProperties() {
        this(true, 60_000L, 0, 8, null, null);
    }

    /**
     * 解析后的线程池核心大小。{@code toolExecutorCore=0} 时按 CPU*2 计算,但不会超过
     * {@code toolExecutorMax}(ThreadPoolExecutor 要求 core ≤ max)。
     */
    public int resolvedCorePoolSize() {
        int c = toolExecutorCore > 0 ? toolExecutorCore : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);
        return Math.min(c, toolExecutorMax);
    }

    public record ResultCache(
            boolean enabled,
            long maxTotalBytes,
            Duration ttl,
            int autoInlineByteBudget
    ) {
        public ResultCache {
            if (maxTotalBytes <= 0) maxTotalBytes = 524_288_000L;
            if (ttl == null) ttl = Duration.ofDays(7);
            if (autoInlineByteBudget <= 0) autoInlineByteBudget = 16_384;
        }
    }

    /**
     * 中高风险工具授权策略。
     *
     * <ul>
     *   <li>{@code PROMPT} —— 每一次都问用户（默认）</li>
     *   <li>{@code AUTO_APPROVE} —— 全自动放行（开发模式；同 SessionState.autoApprove=true）</li>
     *   <li>{@code AUTO_DENY} —— 全自动拒绝（paranoid 模式）</li>
     * </ul>
     *
     * <p>另可通过 {@code alwaysAllow} 预先放行指定工具（不区分 risk 等级），
     * 等价于会话级 "always"。
     */
    public record Authorization(
            Mode mode,
            List<String> alwaysAllow
    ) {
        public enum Mode { PROMPT, AUTO_APPROVE, AUTO_DENY }

        public Authorization {
            if (mode == null) mode = Mode.PROMPT;
            if (alwaysAllow == null) alwaysAllow = List.of();
        }

        public Authorization() {
            this(Mode.PROMPT, List.of());
        }
    }
}
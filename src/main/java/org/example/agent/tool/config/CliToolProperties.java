package org.example.agent.tool.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
        ResultCache resultCache
) {

    public CliToolProperties {
        if (defaultTimeoutMs <= 0) defaultTimeoutMs = 60_000L;
        if (toolExecutorCore < 0) toolExecutorCore = 0;
        if (toolExecutorMax <= 0) toolExecutorMax = 8;
        if (resultCache == null) {
            resultCache = new ResultCache(true, 524_288_000L, Duration.ofDays(7), 16_384);
        }
    }

    public CliToolProperties() {
        this(true, 60_000L, 0, 8, null);
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
}

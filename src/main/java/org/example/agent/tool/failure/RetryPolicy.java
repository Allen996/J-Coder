package org.example.agent.tool.failure;

import lombok.Builder;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 重试策略。
 *
 * <p>默认配置（见 5.6.3）：
 * <ul>
 *   <li>maxAttempts = 3（含首次）</li>
 *   <li>backoff = [500, 1000, 2000] ms</li>
 *   <li>appliesTo = [TRANSIENT]</li>
 *   <li>同 args 重试</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>只对 {@link FailureKind#TRANSIENT} 触发 —— PARAM/LOGIC 立刻返回，由 LLM 决定</li>
 *   <li>不引入 jitter —— v1 范围里并发 shell 上限 4，jitter 收益小</li>
 *   <li>重试消耗 step 预算（由调用方负责，RetryPolicy 本身不感知 budget）</li>
 *   <li>支持通过 builder 覆盖（任务级 budget 注入）</li>
 * </ul>
 */
@Getter
@Component
public class RetryPolicy {

    private final int maxAttempts = 3;
    private final long[] backoffMillis = new long[]{500L, 1000L, 2000L};
    private final Set<FailureKind> appliesTo = EnumSet.of(FailureKind.TRANSIENT);

    /**
     * 判断给定的失败 kind 是否应触发重试。
     */
    public boolean shouldRetry(FailureKind kind) {
        return kind != null && appliesTo.contains(kind);
    }

    /**
     * 返回指定 attempt 之后的等待毫秒。attempt 从 1 开始（attempt=1 表示第一次重试之前的等待）。
     */
    public long backoffFor(int attempt) {
        if (attempt < 1 || backoffMillis.length == 0) {
            return 0L;
        }
        int idx = Math.min(attempt - 1, backoffMillis.length - 1);
        return backoffMillis[idx];
    }

    /**
     * 在指定 sleep 钩子下执行可调用对象。sleep 钩子用于测试时跳过真实等待。
     *
     * @param call        业务逻辑（返回结果或抛异常）
     * @param classify    异常分类器（用于判定是否重试）
     * @param sleeper     等待钩子（生产环境传 {@code Thread::sleep}，测试传 noop）
     * @param onRetry     重试前的回调（用于发事件 / 计数）；attempt 是 1-indexed 的重试序号
     * @param <T>         返回值类型
     * @return call 的返回值
     * @throws Throwable 最终未成功时的异常（已耗尽重试次数 或 不在 appliesTo 集合内）
     */
    public <T> T execute(Callable<T> call,
                         FailureClassifier classify,
                         Sleeper sleeper,
                         OnRetry onRetry) throws Throwable {
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.call();
            } catch (Throwable t) {
                last = t;
                FailureKind kind = classify.classify(t);

                if (!shouldRetry(kind)) {
                    throw t;
                }
                if (attempt >= maxAttempts) {
                    // 已达上限 —— 抛出最后一次异常
                    throw t;
                }

                long sleep = backoffFor(attempt);
                if (onRetry != null) {
                    onRetry.onRetry(attempt, kind, t, sleep);
                }
                if (sleep > 0) {
                    sleeper.sleep(sleep);
                }
            }
        }
        // 不会到这里 —— 循环要么 return 要么 throw
        if (last != null) throw last;
        throw new IllegalStateException("RetryPolicy.execute fell through without result");
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface OnRetry {
        void onRetry(int attempt, FailureKind kind, Throwable cause, long nextSleepMs);
    }

    /**
     * 生产环境默认 Sleeper：直接调 {@link Thread#sleep(long)}，被中断时恢复中断标志后抛。
     */
    public static final Sleeper DEFAULT_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
    };
}

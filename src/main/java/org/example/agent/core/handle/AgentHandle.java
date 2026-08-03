package org.example.agent.core.handle;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 调用方持有的句柄，用于：
 *  - 取回本次执行的 executionId
 *  - 主动取消（cancelNow）
 *  - 查询状态（isDone / isCancelled）
 *
 * cancel 通过 AgentRuntime 内部的 ConcurrentHashMap<executionId, FluxSink>
 * 完成协作式中断。SSE 客户端断开时 AgentRuntimeImpl 也会自动 cancel。
 */
@Getter
@ToString
public class AgentHandle {

    private final String executionId;
    private final Instant issuedAt;

    private final AtomicBoolean cancelled;
    private final AtomicBoolean done;
    private final AtomicReference<String> reason;
    private final Runnable cancelAction;

    @Builder
    public AgentHandle(String executionId, Runnable cancelAction) {
        this.executionId = executionId;
        this.cancelAction = cancelAction == null ? () -> {} : cancelAction;
        this.cancelled = new AtomicBoolean(false);
        this.done = new AtomicBoolean(false);
        this.reason = new AtomicReference<>("running");
        this.issuedAt = Instant.now();
    }

    /** 触发协作式取消。多次调用幂等。 */
    public void cancelNow() {
        if (cancelled.compareAndSet(false, true)) {
            reason.set("cancelled");
            try {
                cancelAction.run();
            } catch (Exception ignored) {
                // cancel 失败不抛出，由 AgentRuntime 在订阅链上再回收
            }
        }
    }

    /** 由 AgentRuntime 在执行彻底结束时通知句柄。 */
    public void markDone(String finishReason) {
        done.set(true);
        reason.set(finishReason);
    }

    public boolean isCancelled() {
        return cancelled.get() || "cancelled".equalsIgnoreCase(reason.get());
    }

    public boolean isDone() {
        return done.get();
    }

    public String currentReason() {
        return reason.get();
    }
}

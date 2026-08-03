package org.example.agent.core.task.verify;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 验证执行结果。
 *
 * <p>{@code exitCode == 0} 表示通过；非 0 表示失败。
 * 完整 stdout+stderr 已落盘到 {@code verify.log}，{@code logTail} 是末段摘要。
 */
@Getter
@Builder
@ToString(of = {"exitCode", "durationMs"})
public final class VerifyResult {

    @Builder.Default
    private final int exitCode = -1;

    @Builder.Default
    private final long durationMs = 0L;

    @Builder.Default
    private final String command = "";

    @Builder.Default
    private final String logTail = "";

    @Builder.Default
    private final Instant startedAt = Instant.now();

    @Builder.Default
    private final Instant finishedAt = Instant.now();

    public boolean passed() {
        return exitCode == 0;
    }
}
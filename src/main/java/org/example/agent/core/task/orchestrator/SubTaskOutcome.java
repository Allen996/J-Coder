package org.example.agent.core.task.orchestrator;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * SubTask 内部执行的中间结果（TaskOrchestrator 内部使用）。
 *
 * <p>由 TaskLoopObserver 聚合一个 SubTask 内 ReAct 循环的工具调用，得出 SubTask 是
 * 显式 complete / 显式 fail / 还是预算耗尽 / 还是其它（视为 fail）。
 */
@Getter
@Builder
@ToString
public final class SubTaskOutcome {

    public enum Kind {
        COMPLETED_EXPLICIT,   // LLM 显式调用了 complete_subtask
        FAILED_EXPLICIT,      // LLM 显式调用了 fail_subtask
        SKIPPED_EXPLICIT,     // LLM 显式调用了 skip_subtask
        BUDGET_EXHAUSTED,     // step budget 用尽
        ERROR                 // 其它异常
    }

    @Builder.Default
    private final Kind kind = Kind.ERROR;

    @Builder.Default
    private final String note = "";

    @Builder.Default
    private final String failureReason = "unknown";

    @Builder.Default
    private final int attempts = 1;

    @Builder.Default
    private final int stepsTaken = 0;
}
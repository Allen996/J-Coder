package org.example.agent.core.result;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.event.AgentEvent;
import org.example.agent.core.record.AgentExecutionRecord;
import org.example.agent.core.reason.FinishReason;

import java.util.List;

/**
 * AgentRuntime.execute() 的同步返回值。
 *
 * 调用方拿到 final answer 即可；同时持有全量 eventLog 用于审计 / 重放 / 前端回放 UI。
 * AgentRuntime.stream() 不返回本类型，而是通过 Flux<AgentEvent> 增量推送，
 * 最终一次 finish 信号表示本结构已被完整发出。
 */
@Getter
@Builder
@ToString
public class AgentExecutionResult {

    /** 终止原因，正常结束 = FINISH，异常被预算掐断 = 各类 _LIMIT。 */
    private final FinishReason reason;

    /** 最终给到用户的答案文本（AssistantMessage.getText() 的累加）。 */
    private final String finalAnswer;

    /** 关联到本此执行的 executionId。可通过 ReplayService 用它重放。 */
    private final String executionId;

    /** 全量事件流。失败 / 取消场景下也至少有一条 FINISH 或 ERROR。 */
    private final List<AgentEvent> eventLog;

    /** 累计 token 消耗。来源：TokenBudgetObserver 在结束时的快照。 */
    private final long totalTokensUsed;

    /** 总执行步数（含模型步与工具步）。 */
    private final int totalSteps;

    /** 原始执行快照，调用方如果要做断点恢复直接使用。 */
    private final AgentExecutionRecord record;
}

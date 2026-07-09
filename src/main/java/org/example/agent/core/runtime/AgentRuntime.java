package org.example.agent.core.runtime;

import org.example.agent.core.handle.AgentHandle;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.result.AgentExecutionResult;
import org.example.agent.core.task.AgentTask;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Agent 运行时门面。
 *
 * 把现有 ChatController / ChatService / AiOpsService 散落的 "createReactAgent + call + SSE"
 * 流水线统一收敛成三个动词。任何业务方通过依赖注入拿到本接口即可：
 *  - execute：阻塞返回最终结果 + 全量 eventLog + 快照
 *  - stream：实时推送 AgentEvent 流（用于 SSE）
 *  - cancel：协作式取消
 *
 * 业务侧不再直接接触 ReactAgent.builder() / ChatModel / ToolCallback 这些 Spring AI Alibaba 细节。
 */
public interface AgentRuntime {

    /** 默认的 executor 执行阻塞返回。 */
    AgentExecutionResult execute(AgentTask task);

    /** 增量返回 AgentEvent 事件流，最后以 FINISH 或 ERROR 收尾。 */
    Flux<org.example.agent.core.event.AgentEvent> stream(AgentTask task);

    /** 协作式取消，副作用是把同一个 executionId 的订阅链 complete。 */
    AgentHandle cancel(String executionId);

    /**
     * 注册全局观察者（适用于所有任务）。4.1 阶段主要放 TokenBudgetObserver / LoopStepObserver /
     * TimeoutObserver / AuditLoggerObserver 等。调用方在 Spring 启动期注册一次。
     */
    void registerObserver(ReActLoopObserver observer);

    /** 返回已注册的全局观察者列表，便于调试 / 测试断言。 */
    List<ReActLoopObserver> registeredObservers();
}

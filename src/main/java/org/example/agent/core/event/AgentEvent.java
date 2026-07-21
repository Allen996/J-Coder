package org.example.agent.core.event;

import java.time.Instant;

/**
 * ReAct 循环内可观测事件的根接口。
 *
 * Sealed 设计：每个步骤都有明确类型，event 流的消费方可以 pattern-match
 * 地分发。AgentRuntimeImpl 会在每次关键状态变更时 emit 一条事件给观察者链。
 *
 * 与 StepRecord 的关系：
 *  - AgentEvent：流式、瞬时（用于实时订阅）
 *  - StepRecord：落盘、聚合（用于审计 / 重放）
 *  通常一次状态变更既 dispatch 事件，也 append 一条 StepRecord。
 *
 * 方法命名为 bean 风格（与 Lombok @Getter 保持一致），子类直接 @Getter 即可自动实现。
 */
public sealed interface AgentEvent
        permits ThoughtEvent,
                ActionPreCheckEvent,
                ActionInvokedEvent,
                ObservationEvent,
                RollbackEvent,
                LoopBudgetEvent,
                TokenBudgetEvent,
                FinishEvent,
                LoopErrorEvent {

    /** 执行 id，跨事件唯一标识一次 task。 */
    String getExecutionId();

    /** 事件时间戳。 */
    Instant getAt();

    /** 当前步号（起始为 1）。 */
    int getStepIndex();

    /** 事件类型的稳定名（用于日志 / metric tag）。 */
    String getType();
}

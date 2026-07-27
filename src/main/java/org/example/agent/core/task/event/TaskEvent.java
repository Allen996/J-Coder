package org.example.agent.core.task.event;

import java.time.Instant;

/**
 * 任务系统事件根接口（part5 §8.8）。
 *
 * <p>Sealed 设计：与 {@link org.example.agent.core.event.AgentEvent} 平行，
 * 由 {@link TaskEventPublisher} 派发给独立的 TaskObserver 链。
 *
 * <p>任务事件不混进 ReActLoop 的 event 流，避免污染单次执行的 step 计数。
 */
public sealed interface TaskEvent
        permits TaskPlanCreatedEvent,
                SubTaskStartedEvent,
                SubTaskCompletedEvent,
                SubTaskFailedEvent,
                VerifyStartedEvent,
                VerifyPassedEvent,
                VerifyFailedEvent,
                PlanFinishedEvent {

    String getPlanId();

    Instant getAt();

    String getType();
}
package org.example.agent.core.task.event;

/**
 * 任务事件订阅接口（part5 §8.8）。
 *
 * <p>独立于 {@link org.example.agent.core.observer.ReActLoopObserver}，
 * 由 {@link TaskEventPublisher} 派发。实现类同样需要保持 idempotent，
 * 单 listener 异常不能阻断其它 listener。
 */
public interface TaskObserver {

    void onTaskPlanCreated(TaskPlanCreatedEvent event);

    void onSubTaskStarted(SubTaskStartedEvent event);

    void onSubTaskCompleted(SubTaskCompletedEvent event);

    void onSubTaskFailed(SubTaskFailedEvent event);

    void onVerifyStarted(VerifyStartedEvent event);

    void onVerifyPassed(VerifyPassedEvent event);

    void onVerifyFailed(VerifyFailedEvent event);

    void onPlanFinished(PlanFinishedEvent event);
}
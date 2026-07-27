package org.example.agent.core.task.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * TaskEvent 派发器（part5 §8.8）。
 *
 * <p>独立于 AgentRuntime 的 observer 链 —— 任务事件不污染 ReAct 的 step 计数。
 * 注册方式：通过 {@link #register(TaskObserver)} 注册一个全局 observer
 * （例如 CLI 渲染、事件总线），所有事件同步派发。
 */
@Slf4j
@Component
public class TaskEventPublisher {

    private final List<TaskObserver> observers = new CopyOnWriteArrayList<>();
    private final List<Consumer<TaskEvent>> listeners = new CopyOnWriteArrayList<>();

    public void register(TaskObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    /** 轻量监听：直接消费 {@link TaskEvent}（用于事件总线 / 测试断言）。 */
    public void register(Consumer<TaskEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public List<TaskObserver> observers() {
        return List.copyOf(observers);
    }

    public void publish(TaskEvent event) {
        for (TaskObserver o : observers) {
            try {
                dispatch(o, event);
            } catch (Exception ex) {
                log.warn("TaskObserver dispatch failed: {}", ex.getMessage(), ex);
            }
        }
        for (Consumer<TaskEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (Exception ex) {
                log.warn("TaskEvent listener failed: {}", ex.getMessage(), ex);
            }
        }
    }

    private void dispatch(TaskObserver o, TaskEvent event) {
        if (event instanceof TaskPlanCreatedEvent e) o.onTaskPlanCreated(e);
        else if (event instanceof SubTaskStartedEvent e) o.onSubTaskStarted(e);
        else if (event instanceof SubTaskCompletedEvent e) o.onSubTaskCompleted(e);
        else if (event instanceof SubTaskFailedEvent e) o.onSubTaskFailed(e);
        else if (event instanceof VerifyStartedEvent e) o.onVerifyStarted(e);
        else if (event instanceof VerifyPassedEvent e) o.onVerifyPassed(e);
        else if (event instanceof VerifyFailedEvent e) o.onVerifyFailed(e);
        else if (event instanceof PlanFinishedEvent e) o.onPlanFinished(e);
    }
}
package org.example.agent.core.task.tool;

import org.example.agent.core.task.orchestrator.TaskLoopObserver;
import org.springframework.stereotype.Component;

/**
 * 当前线程活跃的 {@link TaskLoopObserver} 容器（part5 §8.8）。
 *
 * <p>为了让 {@link TaskPlanTools}（被 LLM 调用的工具）在工具调用后能"通知"当前 SubTask 的 observer，
 * 我们把当前 observer 用 ThreadLocal 暴露出来。orchestrator 在 runActivePlan() 进入 executor 前 bind，
 * 结束后 clear。
 */
@Component
public class TaskLoopObserverHub {

    private final ThreadLocal<TaskLoopObserver> current = new ThreadLocal<>();

    public void bind(TaskLoopObserver observer) {
        current.set(observer);
    }

    public void clear() {
        current.remove();
    }

    public TaskLoopObserver current() {
        return current.get();
    }
}
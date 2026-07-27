package org.example.agent.core.task.scheduler;

/**
 * TaskScheduler 调度异常（part5 §8.4）。
 *
 * <p>用于：DAG 有环 / 依赖未满足 / 找不到可执行 SubTask 等。
 */
public class TaskSchedulerException extends RuntimeException {
    public TaskSchedulerException(String message) {
        super(message);
    }
}
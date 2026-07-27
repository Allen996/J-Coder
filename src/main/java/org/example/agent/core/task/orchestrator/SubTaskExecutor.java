package org.example.agent.core.task.orchestrator;

import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.TaskPlan;

/**
 * SubTask 执行抽象（part5 §8.8 "执行模型"）。
 *
 * <p>把 orchestrator 与具体运行时解耦 —— 默认实现走 {@code AgentRuntime.execute}，
 * 测试桩可以同步返回固定 outcome 而不真正调 LLM。
 */
public interface SubTaskExecutor {

    /**
     * 同步跑一次 SubTask 的 ReAct 循环。返回该次执行的 {@link SubTaskOutcome}。
     *
     * @param plan     所属计划（用于构造 AgentTask 的 promptVariables）
     * @param sub      要执行的 SubTask（必须 IN_PROGRESS）
     * @param observer 该 SubTask 专属的 {@link TaskLoopObserver}，用于追踪 complete_subtask 等调用
     * @return 执行结果
     */
    SubTaskOutcome execute(TaskPlan plan, SubTask sub, TaskLoopObserver observer);
}
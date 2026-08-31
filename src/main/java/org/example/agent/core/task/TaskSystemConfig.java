package org.example.agent.core.task;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 任务系统 bean 装配。
 *
 * <p>把 projectRoot、VERIFY 失败上限等可调参数集中放这里，便于测试覆盖。
 * 同时暴露 {@link #taskAsyncExecutor} 作为任务编排子系统所有后台线程的统一来源
 * —— TaskOrchestrator 的异步 mid-term 落盘、后续扩展（缓存清理等）都从这里取，
 * 避免散落的 new Thread / Executors.newXxx 调用。
 *
 * <p>阶段 2 删除 TaskOrchestratorConfig —— 不再有 VERIFY/FIX/SKIP 状态机,
 * projectRoot 在 DagStateRepository 构造器读取。
 */
@Configuration
public class TaskSystemConfig {

    private static final Logger log = LoggerFactory.getLogger(TaskSystemConfig.class);

    /** 任务编排子系统统一的 TaskExecutor Bean 名称。 */
    public static final String TASK_ASYNC_EXECUTOR_BEAN = "taskAsyncExecutor";

    /** 异步任务专用线程池实例（@PreDestroy 关闭）。 */
    private ThreadPoolTaskExecutor taskAsyncExecutorInstance;

    /**
     * 任务编排子系统统一 TaskExecutor。
     *
     * <p>设计要点：
     * <ul>
     *   <li>核心 2、最大 4、队列 100 —— 中等规模 plan 在 SubTask 完成高峰时并发落盘 mid-term 不超过 SubTask 数。</li>
     *   <li>线程名前缀 {@code task-async-} —— 便于排查 jstack。</li>
     *   <li>{@code waitForTasksToCompleteOnShutdown=true} —— 容器关闭时尽量把已入队任务跑完，
     *       避免 mid-term 落盘丢失（落盘非关键路径，但丢了会让"优雅关闭"语义打折）。</li>
     *   <li>{@code awaitTerminationSeconds=5} —— 给一个硬上限，避免异常任务把容器关闭卡住。</li>
     * </ul>
     *
     * <p>使用方必须 {@code @Qualifier("taskAsyncExecutor") TaskExecutor} 注入。
     * 不要在本类以外的任何地方自行 new ExecutorService —— 整个编排子系统的后台线程统一收口在这里。
     */
    @Bean(name = TASK_ASYNC_EXECUTOR_BEAN)
    public TaskExecutor taskAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("task-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        this.taskAsyncExecutorInstance = executor;
        log.info("TaskSystemConfig.taskAsyncExecutor initialized: core=2 max=4 queue=100 prefix=task-async-");
        return executor;
    }

    @PreDestroy
    public void shutdownExecutors() {
        if (taskAsyncExecutorInstance != null) {
            log.info("TaskSystemConfig shutdown: waiting for task-async executor to drain");
            taskAsyncExecutorInstance.shutdown();
        }
    }
}
package org.example.agent.core.task;

import org.example.agent.core.task.orchestrator.TaskOrchestratorConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 任务系统 bean 装配（part5 §8.11）。
 *
 * <p>把 projectRoot、VERIFY 失败上限等可调参数集中放这里，便于测试覆盖。
 */
@Configuration
public class TaskSystemConfig {

    @Bean
    public TaskOrchestratorConfig taskOrchestratorConfig(
            @Value("${cli.project-root:}") String projectRootConfig) {
        Path root = projectRootConfig == null || projectRootConfig.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(projectRootConfig);
        return TaskOrchestratorConfig.builder()
                .projectRoot(root)
                .maxVerifyFailures(2)
                .budgetRetriedMultiplier(1.5)
                .retryBudgetRelaxed(true)
                .build();
    }
}
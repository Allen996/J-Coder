package org.example.agent.core.task.subagent.tool;

import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.dag.DagStateRepository;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AppendSubtaskTool 阶段 2 测试 —— 验证工具接到 TaskOrchestrator.appendSubtask。
 */
class AppendSubtaskToolTest {

    @TempDir
    Path tempDir;

    TaskOrchestrator orchestrator;
    AppendSubtaskTool tool;

    @BeforeEach
    void setUp() {
        DagStateRepository repository = new DagStateRepository(tempDir);
        repository.init();
        orchestrator = new TaskOrchestrator(repository,
                task -> new org.example.agent.core.task.subagent.SubAgentResult(
                        task.taskId(),
                        org.example.agent.core.task.subagent.SubAgentStatus.COMPLETED,
                        "ok", "", List.of(), List.of(), 0, ""),
                new SyncTaskExecutor(),
                null);
        tool = new AppendSubtaskTool(orchestrator);
    }

    @Test
    void appendsAfterCreatePlan() {
        orchestrator.createPlan("s-1", List.of(DagNode.pending("st-1", "first", "d", List.of(), "o")));

        String result = tool.appendSubtask("st-2", "second", "d", List.of("st-1"), "o");
        assertTrue(result.contains("appended") && result.contains("st-2"),
                "expected appended; got: " + result);
    }

    @Test
    void missingTaskIdReturnsError() {
        String result = tool.appendSubtask("", "t", "d", List.of(), "o");
        assertTrue(result.contains("[error]"), "empty taskId should error; got: " + result);
    }

    @Test
    void missingTitleReturnsError() {
        String result = tool.appendSubtask("st-1", "", "d", List.of(), "o");
        assertTrue(result.contains("[error]"), "empty title should error; got: " + result);
    }

    @Test
    void noActivePlanReturnsError() {
        String result = tool.appendSubtask("st-1", "t", "d", List.of(), "o");
        assertTrue(result.contains("[error]"), "no active plan should error; got: " + result);
    }
}
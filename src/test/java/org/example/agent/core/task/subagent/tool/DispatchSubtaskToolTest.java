package org.example.agent.core.task.subagent.tool;

import org.example.agent.core.task.dag.DagGraph;
import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.dag.DagStateRepository;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.core.task.subagent.SubAgentRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DispatchSubtaskTool 阶段 2 测试 —— 验证工具调到 TaskOrchestrator.dispatchSubtask。
 *
 * <p>阶段 2 起工具不再直接调 SubAgentRunner,而是通过 TaskOrchestrator 中转。
 * 验证工具的契约：参数透传 / running 状态返回 / 错误捕获。
 */
class DispatchSubtaskToolTest {

    @TempDir
    Path tempDir;

    TaskOrchestrator orchestrator;
    DispatchSubtaskTool tool;

    @BeforeEach
    void setUp() {
        DagStateRepository repository = new DagStateRepository(tempDir);
        repository.init();
        SubAgentRunner noopRunner = task ->
                new org.example.agent.core.task.subagent.SubAgentResult(
                        task.taskId(),
                        org.example.agent.core.task.subagent.SubAgentStatus.COMPLETED,
                        "ok", "", List.of(), List.of(), 0, "");
        orchestrator = new TaskOrchestrator(repository, noopRunner, new SyncTaskExecutor(), null);
        tool = new DispatchSubtaskTool(orchestrator);
    }

    @Test
    void dispatchedReturnsRunning() {
        orchestrator.createPlan("session-1", List.of(DagNode.pending("st-1", "first", "d", List.of(), "o")));
        String result = tool.dispatchSubtask("st-1", "first", "d", "o", null, 5000L, null);
        assertTrue(result.contains("running"), "expected running; got: " + result);
        assertTrue(result.contains("st-1"));
    }

    @Test
    void dispatchesUnknownTaskIdReturnsError() {
        orchestrator.createPlan("session-1", List.of(DagNode.pending("st-1", "first", "d", List.of(), "o")));
        String result = tool.dispatchSubtask("st-9", "x", "d", "o", null, 5000L, null);
        assertTrue(result.contains("[error]"), "expected error; got: " + result);
    }

    @Test
    void dispatchesWithoutActivePlanReturnsError() {
        String result = tool.dispatchSubtask("st-1", "x", "d", "o", null, 5000L, null);
        assertTrue(result.contains("[error]"), "expected error; got: " + result);
    }

    @Test
    void dispatchesWithUnmetDependencyReturnsError() {
        orchestrator.createPlan("session-1", List.of(
                DagNode.pending("st-1", "first", "d", List.of(), "o"),
                DagNode.pending("st-2", "second", "d", List.of("st-1"), "o")));
        String result = tool.dispatchSubtask("st-2", "x", "d", "o", null, 5000L, null);
        assertTrue(result.contains("[error]"), "expected error; got: " + result);
    }
}
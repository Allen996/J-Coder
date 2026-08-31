package org.example.agent.core.task.subagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * CreatePlanTool 阶段 2 测试 —— 验证 JSON 解析 + TaskOrchestrator.createPlan 集成。
 */
class CreatePlanToolTest {

    @TempDir
    Path tempDir;

    CreatePlanTool tool;

    @BeforeEach
    void setUp() {
        DagStateRepository repository = new DagStateRepository(tempDir);
        repository.init();
        TaskOrchestrator orchestrator = new TaskOrchestrator(repository,
                task -> new org.example.agent.core.task.subagent.SubAgentResult(
                        task.taskId(),
                        org.example.agent.core.task.subagent.SubAgentStatus.COMPLETED,
                        "ok", "", List.of(), List.of(), 0, ""),
                new SyncTaskExecutor(),
                null);
        tool = new CreatePlanTool(new ObjectMapper(), orchestrator);
    }

    @Test
    void validSubtasksAccepted() {
        String json = "[{\"taskId\":\"st-1\",\"title\":\"t1\",\"description\":\"d1\","
                + "\"dependsOn\":[],\"expectedOutput\":\"o1\"},"
                + "{\"taskId\":\"st-2\",\"title\":\"t2\",\"description\":\"d2\","
                + "\"dependsOn\":[\"st-1\"],\"expectedOutput\":\"o2\"}]";
        String result = tool.createPlan("goal", json, "sid");
        assertTrue(result.contains("plan created") && result.contains("2 nodes"),
                "expected plan created; got: " + result);
    }

    @Test
    void missingSessionIdRejected() {
        String json = "[{\"taskId\":\"st-1\",\"title\":\"t1\",\"description\":\"d1\","
                + "\"dependsOn\":[],\"expectedOutput\":\"o1\"}]";
        String result = tool.createPlan("goal", json, null);
        assertTrue(result.contains("[error]") && result.contains("sessionId is required"),
                "missing sessionId should error; got: " + result);
    }

    @Test
    void missingFieldRejected() {
        String json = "[{\"taskId\":\"st-1\",\"title\":\"t1\",\"description\":\"d1\",\"dependsOn\":[]}]";
        String result = tool.createPlan("goal", json, "sid");
        assertTrue(result.contains("[error]") && result.contains("missing field"),
                "missing field should error; got: " + result);
    }

    @Test
    void invalidJsonRejected() {
        String result = tool.createPlan("goal", "not json", "sid");
        assertTrue(result.contains("[error]") && result.contains("invalid subtasks JSON"),
                "invalid JSON should error; got: " + result);
    }
}
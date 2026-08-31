package org.example.agent.core.task.subagent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code create_plan} 工具（阶段 2 接通 TaskOrchestrator.createPlan）。
 *
 * <p>主 Agent 专属。LLM 通过本工具声明初始 DAG，每个节点含 taskId/title/description/
 * dependsOn/expectedOutput（必填五元组）。
 */
@Slf4j
@Component
public class CreatePlanTool {

    private final ObjectMapper mapper;
    private final TaskOrchestrator orchestrator;

    @Autowired
    public CreatePlanTool(ObjectMapper mapper, TaskOrchestrator orchestrator) {
        this.mapper = mapper;
        this.orchestrator = orchestrator;
    }

    @Tool(description = "创建一个初始 TaskPlan,把复杂任务拆成 DAG 节点。"
            + "subtasks 必须是合法 JSON 数组,每项含 taskId/title/description/dependsOn/expectedOutput。"
            + "taskId 唯一(例如 st-1, st-2),dependsOn 是数组(无依赖传 [])。"
            + "调用后 plan.json + dag-state.json 落盘,plan 进入 RUNNING。"
            + "后续通过 dispatch_subtask 派发具体节点,通过 append_subtask 增加新节点。")
    public String createPlan(
            @ToolParam(description = "计划目标,自然语言一句话") String goal,
            @ToolParam(description = "子任务 JSON 数组,例如 "
                    + "[{\"taskId\":\"st-1\",\"title\":\"...\",\"description\":\"...\","
                    + "\"dependsOn\":[],\"expectedOutput\":\"...\"}]")
            String subtasksJson,
            @ToolParam(description = "sessionId(必填,plan 与 dag-state 都按 sessionId 组织)") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "[error] sessionId is required";
        }
        List<Map<String, Object>> specs;
        try {
            specs = mapper.readValue(subtasksJson, new TypeReference<>() {});
        } catch (Exception ex) {
            return "[error] invalid subtasks JSON: " + ex.getMessage();
        }
        // 校验：每项必须有 taskId/title/description/dependsOn/expectedOutput
        List<DagNode> nodes = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            Map<String, Object> s = specs.get(i);
            if (s == null) {
                return "[error] subtasks[" + i + "] is null";
            }
            for (String f : List.of("taskId", "title", "description", "dependsOn", "expectedOutput")) {
                if (!s.containsKey(f)) {
                    return "[error] subtasks[" + i + "] missing field: " + f;
                }
            }
            String taskId = String.valueOf(s.get("taskId"));
            String title = String.valueOf(s.get("title"));
            String desc = String.valueOf(s.get("description"));
            @SuppressWarnings("unchecked")
            List<String> deps = (List<String>) s.get("dependsOn");
            String expOut = String.valueOf(s.get("expectedOutput"));
            nodes.add(DagNode.pending(taskId, title, desc, deps, expOut));
        }
        try {
            var graph = orchestrator.createPlan(sessionId, nodes);
            return "plan created: " + graph.getPlanId() + " with " + graph.size() + " nodes (sessionId=" + sessionId + "). "
                    + "Use dispatch_subtask to start executing nodes.";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return "[error] " + ex.getMessage();
        }
    }
}
package org.example.agent.core.task.subagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code append_subtask} 工具（阶段 2 接通 TaskOrchestrator.appendSubtask）。
 *
 * <p>主 Agent 专属。在已有 plan 内追加一个新节点（占位，不立即执行）。
 *
 * <p>L.2 决定：append_subtask 依赖 create_plan —— 必须先有 initial DAG 才能 append。
 * dependsOn 引用的 taskId 必须已存在,否则报 [error]。
 */
@Slf4j
@Component
public class AppendSubtaskTool {

    private final TaskOrchestrator orchestrator;

    @Autowired
    public AppendSubtaskTool(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Tool(description = "向当前 DAG 追加一个新节点(不立即执行)。"
            + "必须在 create_plan 之后调用 —— 没有 active plan 时返回错误。"
            + "dependsOn 引用的 taskId 必须已存在,否则报 [error]。"
            + "新节点处于 PENDING 状态,主 Agent 后续通过 dispatch_subtask 派发它。")
    public String appendSubtask(
            @ToolParam(description = "新节点 taskId,必须与现有节点不重复") String taskId,
            @ToolParam(description = "任务标题") String title,
            @ToolParam(description = "任务描述") String description,
            @ToolParam(description = "依赖的 taskId 列表(无依赖传空数组)") List<String> dependsOn,
            @ToolParam(description = "期望产出格式说明") String expectedOutput) {
        if (taskId == null || taskId.isBlank()) return "[error] taskId is required";
        if (title == null || title.isBlank()) return "[error] title is required";
        try {
            orchestrator.appendSubtask(taskId, title, description, dependsOn, expectedOutput);
            return "appended: taskId=" + taskId + " title='" + title + "' dependsOn="
                    + (dependsOn == null ? List.of() : dependsOn);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return "[error] " + ex.getMessage();
        }
    }
}
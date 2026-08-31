package org.example.agent.core.task.subagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code dispatch_subtask} 工具（阶段 2 接通 TaskOrchestrator）。
 *
 * <p>主 Agent 通过本工具派发一个 SubAgent 跑指定任务。异步立即返回 status=running，
 * TaskOrchestrator 在主 loop 下一轮推理前自动 await 所有 pending SubAgent（方案 3 + Q）。
 *
 * <p>主 Agent 专属 —— 在 {@code ToolDescriptorRegistry} 中标记为 {@code mainAgentOnly=true}，
 * SubAgent 启动时不会注册本 bean。
 *
 * <p>约束：
 * <ul>
 *   <li>taskId 必须在当前 plan graph 中已存在（create_plan 或 append_subtask 先声明）</li>
 *   <li>所有依赖必须 COMPLETED（否则抛错,主 Agent 自行调整顺序）</li>
 *   <li>节点不是终态（FAILED 节点可重试,需主 Agent 显式再调 dispatch）</li>
 * </ul>
 */
@Slf4j
@Component
public class DispatchSubtaskTool {

    private final TaskOrchestrator orchestrator;

    @Autowired
    public DispatchSubtaskTool(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Tool(description = "派发一个 SubAgent 跑指定任务。"
            + "taskId 必须与 plan 中已声明的节点一致(create_plan 或 append_subtask 创建的节点)。"
            + "title/description/expectedOutput 用于构造 SubAgent 的 prompt。"
            + "contextFiles 是 SubAgent 启动时建议先读的文件(可选)。"
            + "返回 running 状态——主 loop 在下一轮推理前会自动 await 并把结果汇入上下文。"
            + "失败/超时由主 Agent 在收到 reason 后决定是否重试同一 taskId 或换策略。")
    public String dispatchSubtask(
            @ToolParam(description = "SubAgent id,必须与 plan 中已声明的 taskId 一致") String taskId,
            @ToolParam(description = "任务标题") String title,
            @ToolParam(description = "任务描述(给 SubAgent 的 prompt 主体)") String description,
            @ToolParam(description = "期望产出格式说明,例如 'json 格式的 API 列表' 或 '代码 diff'") String expectedOutput,
            @ToolParam(description = "SubAgent 启动时建议先读的文件路径列表(可选)", required = false) List<String> contextFiles,
            @ToolParam(description = "最大运行时长毫秒,缺省 10 分钟(600000)", required = false) Long timeoutMs,
            @ToolParam(description = "可选:父 Checkpoint id,用于阶段 4 撤销/回溯") String parentCheckpointId) {
        long to = timeoutMs == null || timeoutMs <= 0 ? 10 * 60 * 1000L : timeoutMs;
        try {
            TaskOrchestrator.DispatchResult r = orchestrator.dispatchSubtask(
                    taskId, title, description, expectedOutput, contextFiles, to, parentCheckpointId);
            return "[" + r.taskId() + " " + r.status() + "] " + r.note();
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return "[error] " + ex.getMessage();
        }
    }
}
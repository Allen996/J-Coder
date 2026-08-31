package org.example.agent.core.task.subagent;

import java.util.List;

/**
 * SubAgent 任务的输入契约（阶段 1 引入）。
 *
 * <p>由主 Agent 通过 {@code dispatch_subtask} 工具传入。所有字段必填（除 contextFiles），
 * 缺字段由工具调用方负责补全或校验。
 *
 * @param taskId          SubAgent 标识，也是 SubAgent 的 sessionId（{@code .agent/sessions/{taskId}/}）
 * @param title           任务标题（用于日志 / DAG 节点显示）
 * @param description     任务描述（直接作为 SubAgent system prompt 的任务段）
 * @param expectedOutput  主 Agent 期望的产出格式说明
 * @param contextFiles    初始需要看的文件路径列表（SubAgent prompt 注入"已读"提示，避免重复 read）
 * @param parentSessionId 主 Agent sessionId（用于日志追溯 / 嵌套记录）
 * @param parentCheckpointId 主 Agent 当前 Checkpoint id（可选，用于撤销/回溯；阶段 4 接通）
 * @param timeoutMs       最大运行时长（毫秒），超过则 SubAgentRunner 强制中断并返回 TIMEOUT
 */
public record SubAgentTask(
        String taskId,
        String title,
        String description,
        String expectedOutput,
        List<String> contextFiles,
        String parentSessionId,
        String parentCheckpointId,
        long timeoutMs
) {
    public SubAgentTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (description == null) description = "";
        if (expectedOutput == null) expectedOutput = "";
        if (contextFiles == null) contextFiles = List.of();
        if (parentSessionId == null) parentSessionId = "";
        if (parentCheckpointId == null) parentCheckpointId = "";
        if (timeoutMs <= 0) timeoutMs = 10 * 60 * 1000L; // 默认 10 分钟
    }
}
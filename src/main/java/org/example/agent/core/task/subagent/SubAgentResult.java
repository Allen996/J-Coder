package org.example.agent.core.task.subagent;

import java.util.List;

/**
 * SubAgent 单次执行的输出契约（阶段 1 引入）。
 *
 * @param taskId       对应输入的 taskId
 * @param status       终态（COMPLETED / FAILED / TIMEOUT）
 * @param report       final report 文本（SubAgent 总结）。FAILED/TIMEOUT 时也填，由主 Agent 决定如何利用
 * @param reason       失败 / 超时原因（status=FAILED 或 TIMEOUT 时非空）
 * @param artifacts    SubAgent 创建 / 修改的文件路径列表（项目相对路径或绝对路径）
 * @param toolCalls    SubAgent 工具调用列表（"name:args-hash" 摘要，不含完整内容；主 Agent worklog 嵌套记录用）
 * @param durationMs   实际运行毫秒数
 * @param sessionPath  SubAgent session 目录绝对路径（主 Agent 可通过 inspect_subagent 读取）
 */
public record SubAgentResult(
        String taskId,
        SubAgentStatus status,
        String report,
        String reason,
        List<String> artifacts,
        List<String> toolCalls,
        long durationMs,
        String sessionPath
) {
    public SubAgentResult {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (status == null) status = SubAgentStatus.FAILED;
        if (report == null) report = "";
        if (reason == null) reason = "";
        if (artifacts == null) artifacts = List.of();
        if (toolCalls == null) toolCalls = List.of();
        if (durationMs < 0) durationMs = 0;
        if (sessionPath == null) sessionPath = "";
    }

    public boolean isSuccess() {
        return status == SubAgentStatus.COMPLETED;
    }
}
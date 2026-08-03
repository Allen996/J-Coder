package org.example.agent.tool.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;

/**
 * 工具错误的结构化表示。
 *
 * <p>三件套：
 * <ul>
 *   <li>{@code kind} —— 决定 gateway 走 retry / 反馈 / 回滚哪条路径</li>
 *   <li>{@code errorCode} —— 机器可读，AuditLogger / CliRenderer / 用户回放都靠它</li>
 *   <li>{@code message} + {@code suggestion} —— 给人 / LLM 看的提示</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolError(
        FailureKind kind,
        ToolErrorCode errorCode,
        String message,
        String suggestion
) {

    public static ToolError of(FailureKind kind, ToolErrorCode code, String message) {
        return new ToolError(kind, code, message, null);
    }

    public static ToolError of(FailureKind kind, ToolErrorCode code, String message, String suggestion) {
        return new ToolError(kind, code, message, suggestion);
    }
}

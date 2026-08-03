package org.example.agent.tool;

import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;

/**
 * 沙箱拒绝专用异常。
 *
 * <p>由 PathGate / CommandGate / AuthorizationGate 抛出，<b>不</b>经过工具本身的逻辑。
 * {@link org.example.agent.tool.gateway.ToolGateway} 捕获后翻译成 {@code ToolResult.denied(...)}，
 * LLM 看到的是结构化的拒绝原因（路径越界 / 命令在黑名单内 / 用户未授权），而不是异常堆栈。
 *
 * <p>固定映射到 {@link FailureKind#PARAM} —— 沙箱拒绝一律不重试，告知 LLM 调整路径或命令。
 */
public class ToolDeniedException extends RuntimeException {

    private final ToolErrorCode errorCode;
    private final String suggestion;

    public ToolDeniedException(ToolErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ToolDeniedException(ToolErrorCode errorCode, String message, String suggestion) {
        super(message);
        this.errorCode = errorCode;
        this.suggestion = suggestion;
    }

    public ToolErrorCode getErrorCode() {
        return errorCode;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public FailureKind failureKind() {
        return FailureKind.PARAM;
    }
}

package org.example.agent.tool;

import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;

/**
 * 工具内部抛出的异常，携带结构化错误码。
 *
 * <p>约定：
 * <ul>
 *   <li>工具实现里凡是需要"告诉 LLM 出错了"的场景，抛本异常而不是裸 {@link RuntimeException}</li>
 *   <li>{@link #getErrorCode()} 是机器可读标识，{@link #getSuggestion()} 是给 LLM 的修改提示</li>
 *   <li>异常会被 {@link org.example.agent.tool.gateway.ToolGateway} 捕获并翻译成 {@code ToolResult.error(...)},
 *       不会穿透到 LLM 看到 stacktrace</li>
 *   <li>{@link #failureKind()} 决定后续走 retry / 反馈 / 回滚哪条路径</li>
 * </ul>
 */
public class ToolExecutionException extends RuntimeException {

    private final ToolErrorCode errorCode;
    private final FailureKind kind;
    private final String suggestion;

    public ToolExecutionException(ToolErrorCode errorCode, String message) {
        this(errorCode, message, null, null, null);
    }

    public ToolExecutionException(ToolErrorCode errorCode, String message, String suggestion) {
        this(errorCode, message, suggestion, null, null);
    }

    public ToolExecutionException(ToolErrorCode errorCode, String message, String suggestion, FailureKind kind) {
        this(errorCode, message, suggestion, kind, null);
    }

    public ToolExecutionException(ToolErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, null, cause);
    }

    public ToolExecutionException(ToolErrorCode errorCode, String message, String suggestion, Throwable cause) {
        this(errorCode, message, suggestion, null, cause);
    }

    public ToolExecutionException(ToolErrorCode errorCode,
                                  String message,
                                  String suggestion,
                                  FailureKind kind,
                                  Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.suggestion = suggestion;
        this.kind = kind == null ? FailureKind.LOGIC : kind;
    }

    public ToolErrorCode getErrorCode() {
        return errorCode;
    }

    public FailureKind failureKind() {
        return kind;
    }

    public String getSuggestion() {
        return suggestion;
    }
}

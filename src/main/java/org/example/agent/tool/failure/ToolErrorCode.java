package org.example.agent.tool.failure;

/**
 * 工具错误的封闭错误码集合。
 *
 * <p>每条枚举值与默认 {@link FailureKind} 关联，但具体 kind 仍由异常抛出时显式指定
 * （避免"哪种 IOException 是瞬时哪种是参数错误"靠枚举默认值猜）。
 *
 * <p>错误码命名规范：
 * <ul>
 *   <li>大写下划线，全大写名词</li>
 *   <li>不携带 kind 信息（kind 在 ToolError.kind 字段）</li>
 *   <li>不携带具体路径 / 命令内容（避免泄露到日志聚合系统）</li>
 * </ul>
 */
public enum ToolErrorCode {

    // ===== 参数类 =====
    PATH_NOT_FOUND,
    PATH_OUTSIDE_SANDBOX,
    PATH_BLACKLISTED,
    SHELL_DENIED,
    SHELL_ARG_INJECTION,
    COMMAND_NOT_FOUND,
    WRITE_FILE_CONFLICT,
    INVALID_ARGUMENT,

    // ===== 瞬时类 =====
    TIMEOUT,
    NETWORK_TRANSIENT,
    IO_TRANSIENT,

    // ===== 逻辑类 =====
    SHELL_NONZERO_EXIT,
    GREP_ZERO_HITS,
    GIT_COMMIT_NO_CHANGES,
    INTERNAL_ERROR;

    public FailureKind defaultKind() {
        return switch (this) {
            case PATH_NOT_FOUND, PATH_OUTSIDE_SANDBOX, PATH_BLACKLISTED,
                 SHELL_DENIED, SHELL_ARG_INJECTION, COMMAND_NOT_FOUND,
                 WRITE_FILE_CONFLICT, INVALID_ARGUMENT -> FailureKind.PARAM;
            case TIMEOUT, NETWORK_TRANSIENT, IO_TRANSIENT -> FailureKind.TRANSIENT;
            case SHELL_NONZERO_EXIT, GREP_ZERO_HITS,
                 GIT_COMMIT_NO_CHANGES, INTERNAL_ERROR -> FailureKind.LOGIC;
        };
    }
}

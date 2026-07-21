package org.example.agent.tool.shell;

import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.sandbox.CommandGate;
import org.example.agent.tool.sandbox.ExecutionGate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shell 工具。
 *
 * <p>{@link #runShell} 走完整沙箱链路：
 * {@link CommandGate} 黑名单校验 → {@link ExecutionGate} 槽位申请 → Process 启动 →
 * 限时 + 输出截断 → 退出码解读。
 *
 * <p>用户授权 UI 在 v1 暂未接，HIGH 风险工具默认放行（开发期）。
 * v2 接入授权 UI 后在此加授权闸。
 */
@Component
public class ShellTools {

    private static final int MAX_OUTPUT_BYTES = 100 * 1024;
    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;
    private static final long MAX_TIMEOUT_SECONDS = 600L;

    private final CommandGate commandGate;
    private final ExecutionGate executionGate;

    public ShellTools(CommandGate commandGate, ExecutionGate executionGate) {
        this.commandGate = commandGate;
        this.executionGate = executionGate;
    }

    @Tool(description = "在项目根目录执行 shell 命令，受命令闸 + 执行闸约束。"
            + "timeout 单位秒，默认 30，上限 600。")
    public String runShell(
            @ToolParam(description = "要执行的命令") String command,
            @ToolParam(description = "超时秒数，缺省 30，上限 600", required = false) Integer timeout) {
        // 1. 闸 2：命令黑名单
        String firstWord = commandGate.validate(command);
        boolean whitelisted = commandGate.isWhitelisted(command);

        // 2. 闸 3：执行约束（并发 + 工作目录 + 环境变量）
        long timeoutSec = Math.min(MAX_TIMEOUT_SECONDS,
                timeout == null ? DEFAULT_TIMEOUT_SECONDS : Math.max(1, timeout));

        try (ExecutionGate.ShellPermit permit = executionGate.acquireShellPermit()) {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(executionGate.workingDir().toFile());
            pb.environment().clear();
            pb.environment().putAll(executionGate.safeEnv());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (out.length() < MAX_OUTPUT_BYTES) {
                        out.append(line).append('\n');
                    } else {
                        out.append("…(output truncated, > ").append(MAX_OUTPUT_BYTES).append(" bytes)\n");
                        break;
                    }
                }
            }
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ToolExecutionException(
                        ToolErrorCode.TIMEOUT,
                        "command timed out after " + timeoutSec + "s: " + firstWord,
                        "缩短命令或拆成多步",
                        FailureKind.TRANSIENT);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new ToolExecutionException(
                        ToolErrorCode.SHELL_NONZERO_EXIT,
                        "exit " + exit + " (" + firstWord + ")"
                                + (whitelisted ? "" : " [non-whitelisted]")
                                + "\n" + trim(out, 2000),
                        "检查命令语法与参数",
                        FailureKind.LOGIC);
            }
            return out.toString();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(
                    ToolErrorCode.TIMEOUT,
                    "shell 调用被中断",
                    null, FailureKind.TRANSIENT, ie);
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "shell 调用失败: " + ioe.getMessage(),
                    null, FailureKind.TRANSIENT, ioe);
        }
    }

    @Tool(description = "检查命令是否在 PATH 中可用。返回 'available' 或 'missing'。")
    public String checkCommandExists(
            @ToolParam(description = "命令名（不含参数）") String name) {
        if (name == null || name.isBlank()) {
            throw new ToolExecutionException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "name 为空",
                    "提供命令名", FailureKind.PARAM);
        }
        // 命令名本身也要走命令闸，防止 LLM 探测黑名单命令是否存在
        commandGate.validate(name);
        try (ExecutionGate.ShellPermit permit = executionGate.acquireShellPermit()) {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", "command -v " + name);
            pb.directory(executionGate.workingDir().toFile());
            pb.environment().clear();
            pb.environment().putAll(executionGate.safeEnv());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return out.length() > 0 ? "available" : "missing";
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(
                    ToolErrorCode.TIMEOUT,
                    "check_command_exists 中断",
                    null, FailureKind.TRANSIENT, ie);
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "check_command_exists 失败: " + ioe.getMessage(),
                    null, FailureKind.TRANSIENT, ioe);
        }
    }

    private String trim(StringBuilder sb, int max) {
        if (sb.length() <= max) return sb.toString();
        return sb.substring(0, max) + "\n…(truncated for error message)";
    }
}

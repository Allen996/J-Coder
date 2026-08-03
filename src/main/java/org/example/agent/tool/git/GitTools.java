package org.example.agent.tool.git;

import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.sandbox.ExecutionGate;
import org.example.agent.tool.sandbox.PathGate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Git 工具。
 *
 * <p>通过 {@code git} 命令行调用，避免引入 JGit 依赖。
 * git 已经在 CommandGate 白名单内，命令闸不会拒绝。
 */
@Component
public class GitTools {

    private static final long DEFAULT_TIMEOUT_SECONDS = 30L;

    private final PathGate pathGate;
    private final ExecutionGate executionGate;

    public GitTools(PathGate pathGate, ExecutionGate executionGate) {
        this.pathGate = pathGate;
        this.executionGate = executionGate;
    }

    @Tool(description = "查看 git 工作区与暂存区状态。")
    public String gitStatus() {
        return runGit("status", "--porcelain");
    }

    @Tool(description = "查看 diff。file 不传则整个工作区；staged=true 看暂存区。")
    public String gitDiff(
            @ToolParam(description = "指定文件路径；不传则整个工作区", required = false) String file,
            @ToolParam(description = "true 看暂存区 diff", required = false) Boolean staged) {
        StringBuilder cmd = new StringBuilder("diff");
        if (Boolean.TRUE.equals(staged)) cmd.append(" --staged");
        if (file != null && !file.isBlank()) {
            // 验证 file 在沙箱内
            pathGate.validate(file);
            cmd.append(" -- ").append(file);
        }
        return runGit(cmd.toString());
    }

    @Tool(description = "查看最近 n 条提交。n 缺省 10。")
    public String gitLog(
            @ToolParam(description = "返回条数", required = false) Integer n) {
        int count = n == null ? 10 : Math.max(1, n);
        return runGit("log", "--oneline", "-n", String.valueOf(count));
    }

    @Tool(description = "提交暂存区的变更。addAll=true 时先 git add -A。")
    public String gitCommit(
            @ToolParam(description = "提交信息") String message,
            @ToolParam(description = "true 时先 git add -A", required = false) Boolean addAll) {
        if (message == null || message.isBlank()) {
            throw new ToolExecutionException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "提交信息为空",
                    "提供非空 message", FailureKind.PARAM);
        }
        if (Boolean.TRUE.equals(addAll)) {
            runGit("add", "-A");
        }
        // 先看有没有变更
        String status = runGit("status", "--porcelain");
        if (status.isBlank()) {
            throw new ToolExecutionException(
                    ToolErrorCode.GIT_COMMIT_NO_CHANGES,
                    "暂存区与工作区都没有变更",
                    "先修改文件或 git add",
                    FailureKind.LOGIC);
        }
        return runGit("commit", "-m", message);
    }

    @Tool(description = "查看某次提交 / 引用对应的内容。ref 例如 HEAD、HEAD~1、commit hash。")
    public String gitShow(
            @ToolParam(description = "commit / 引用") String ref) {
        if (ref == null || ref.isBlank()) {
            throw new ToolExecutionException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "ref 为空",
                    "提供 commit hash 或 HEAD~N",
                    FailureKind.PARAM);
        }
        return runGit("show", ref);
    }

    // ============== 内部 ==============

    private String runGit(String... args) {
        try (ExecutionGate.ShellPermit permit = executionGate.acquireShellPermit()) {
            ProcessBuilder pb = new ProcessBuilder(buildArgs(args));
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
                    if (out.length() < 100_000) {
                        out.append(line).append('\n');
                    } else {
                        out.append("…(truncated)\n");
                        break;
                    }
                }
            }
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ToolExecutionException(
                        ToolErrorCode.TIMEOUT,
                        "git " + String.join(" ", args) + " 超时",
                        null, FailureKind.TRANSIENT);
            }
            int exit = process.exitValue();
            if (exit != 0) {
                throw new ToolExecutionException(
                        ToolErrorCode.SHELL_NONZERO_EXIT,
                        "git exit " + exit + ": " + out,
                        "检查命令参数与仓库状态",
                        FailureKind.LOGIC);
            }
            return out.toString();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(
                    ToolErrorCode.TIMEOUT,
                    "git 调用被中断",
                    null, FailureKind.TRANSIENT, ie);
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "git 调用失败: " + ioe.getMessage(),
                    null, FailureKind.TRANSIENT, ioe);
        }
    }

    private String[] buildArgs(String[] tail) {
        String[] all = new String[tail.length + 1];
        all[0] = "git";
        System.arraycopy(tail, 0, all, 1, tail.length);
        return all;
    }
}

package org.example.cli.input;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ! 透传：受白名单约束的 shell 命令执行。
 *
 * 白名单（part1.md §5.3 节选 + Part 1 最小集）：
 *   ls cat head tail wc file stat find grep rg tree du df pwd echo
 *   git (status / diff / log / show / branch)
 *
 * 规则：
 *  - 只允许单 token 命令或多 token 但**不含 shell 元字符**（&, |, ;, >, <, $, `, \\, *, ?, ~）
 *  - 30 秒超时，超时强制 destroy
 *  - 工作目录强制为 projectRoot
 *  - 不走 Authorizer（Part 1 没有工具授权概念）
 */
@Slf4j
@Component
public class ShellPassthrough {

    private static final Set<String> ALLOWED_BASE = Set.of(
            "ls", "cat", "head", "tail", "wc", "file", "stat", "find",
            "grep", "rg", "tree", "du", "df", "pwd", "echo", "printenv",
            "date", "whoami"
    );

    private static final Set<String> ALLOWED_GIT_SUBCMDS = Set.of(
            "status", "diff", "log", "show", "branch", "remote", "rev-parse"
    );

    private static final List<String> FORBIDDEN_CHARS = List.of("&", "|", ";", ">", "<", "$", "`", "\\", "*", "?", "~");

    /** 执行结果 */
    public record ShellResult(int exitCode, String output) {
    }

    public ShellResult run(String commandLine, Path cwd, PrintWriterBridge out) {
        if (commandLine == null || commandLine.isBlank()) {
            return new ShellResult(2, "empty command");
        }
        // 安全检查：禁止 shell 元字符
        for (String forbidden : FORBIDDEN_CHARS) {
            if (commandLine.contains(forbidden)) {
                return new ShellResult(2, "refused: command contains shell meta-char '" + forbidden + "'. " +
                        "Part 1 only supports simple whitelisted verbs. Complex shell use will be available via tools in Part 2.");
            }
        }

        // 切分首词
        String[] tokens = commandLine.trim().split("\\s+");
        if (tokens.length == 0) {
            return new ShellResult(2, "empty command");
        }
        String head = tokens[0];
        if (!isAllowed(head, tokens)) {
            return new ShellResult(2, "refused: '" + head + "' is not in the Part 1 whitelist. " +
                    "Available: " + ALLOWED_BASE + " + git{" + ALLOWED_GIT_SUBCMDS + "}");
        }

        ProcessBuilder pb = new ProcessBuilder(tokens).directory(cwd.toFile()).redirectErrorStream(true);
        try {
            Process proc = pb.start();
            StringBuilder buf = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while ((line = reader.readLine()) != null && lines < 200) {
                    buf.append(line).append('\n');
                    if (out != null) {
                        out.println(line);
                    }
                    lines++;
                }
            }
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new ShellResult(124, buf + "\n(timeout after 30s)");
            }
            int code = proc.exitValue();
            return new ShellResult(code, buf.toString());
        } catch (IOException ex) {
            log.warn("shell passthrough failed: {}", ex.getMessage());
            return new ShellResult(127, "spawn failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ShellResult(130, "interrupted");
        }
    }

    private boolean isAllowed(String head, String[] tokens) {
        if (ALLOWED_BASE.contains(head)) {
            return true;
        }
        if ("git".equals(head) && tokens.length >= 2 && ALLOWED_GIT_SUBCMDS.contains(tokens[1])) {
            return true;
        }
        return false;
    }

    /** 写输出的回调抽象，便于在 ShellPassthrough 测试时 mock。 */
    @FunctionalInterface
    public interface PrintWriterBridge {
        void println(String line);
    }
}
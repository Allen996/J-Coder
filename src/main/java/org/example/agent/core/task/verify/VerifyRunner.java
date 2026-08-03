package org.example.agent.core.task.verify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 验证执行器（part5 §8.7）。
 *
 * <p>职责：
 * <ol>
 *   <li>根据项目类型（pom.xml / build.gradle / package.json）生成 {@link VerifyCommandTemplate}</li>
 *   <li>通过 {@link ProcessBuilder} 启动命令，逐行收集 stdout/stderr（合并输出）</li>
 *   <li>将完整输出落盘到 {@code .agent/tasks/{planId}/verify.log}</li>
 *   <li>返回 {@link VerifyResult}（exitCode + logTail）</li>
 * </ol>
 *
 * <p>注意：启动验证由用户决策（{@code /verify} 或 orchestrator 自动），不是沙箱 shell。
 * 这里直接 {@code ProcessBuilder} 执行，不再过 CommandGate —— VERIFY 子任务本身是受控入口。
 */
@Slf4j
@Component
public class VerifyRunner {

    /** 单条验证命令最长执行时间（10 分钟），超出强 terminate。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 600L;

    /** stdout/stderr 单行截断上限（避免被一行巨型 mvn 输出吃掉 100K）。 */
    private static final int MAX_LINE_BYTES = 8192;

    /** verify.log 落盘最大字节。 */
    private static final int MAX_LOG_BYTES = 1024 * 1024;

    /**
     * 根据项目根目录自动检测构建系统，返回相应的命令模板。
     *
     * <p>优先级：{@code @SpringBootApplication} → MAVEN_COMPILE_TEST_COMPILE；
     * 普通 Maven → MAVEN_COMPILE_TEST；Gradle → GRADLE；npm → NPM_TEST；
     * 都未识别 → GENERIC_BUILD（不执行，仅落占位）。
     */
    public VerifyCommandTemplate detect(Path projectRoot) {
        if (projectRoot == null) {
            return genericTemplate();
        }
        Path pom = projectRoot.resolve("pom.xml");
        Path gradle = projectRoot.resolve("build.gradle");
        Path packageJson = projectRoot.resolve("package.json");

        if (Files.exists(pom)) {
            if (containsSpringBootMarker(projectRoot)) {
                return VerifyCommandTemplate.builder()
                        .kind(VerifyCommandTemplate.Kind.MAVEN_COMPILE_TEST_COMPILE)
                        .commands(List.of("mvn -q compile", "mvn -q test-compile"))
                        .description("Spring Boot project: mvn -q compile && mvn -q test-compile")
                        .build();
            }
            return VerifyCommandTemplate.builder()
                    .kind(VerifyCommandTemplate.Kind.MAVEN_COMPILE_TEST)
                    .commands(List.of("mvn -q compile", "mvn -q test"))
                    .description("Maven: mvn -q compile && mvn -q test")
                    .build();
        }
        if (Files.exists(gradle)) {
            return VerifyCommandTemplate.builder()
                    .kind(VerifyCommandTemplate.Kind.GRADLE)
                    .commands(List.of("./gradlew -q compileJava test"))
                    .description("Gradle: ./gradlew -q compileJava test")
                    .build();
        }
        if (Files.exists(packageJson)) {
            return VerifyCommandTemplate.builder()
                    .kind(VerifyCommandTemplate.Kind.NPM_TEST)
                    .commands(List.of("npm test --silent"))
                    .description("npm: npm test --silent")
                    .build();
        }
        return genericTemplate();
    }

    private static VerifyCommandTemplate genericTemplate() {
        return VerifyCommandTemplate.builder()
                .kind(VerifyCommandTemplate.Kind.GENERIC_BUILD)
                .commands(List.of("echo \"no verify command detected for this project\""))
                .description("generic placeholder")
                .build();
    }

    /**
     * 检测项目是否包含 {@code @SpringBootApplication} 注解（part5 §8.7 规则 2）。
     *
     * <p>启发式：在 src/main/java 下扫描 .java 文件，正则匹配注解签名。
     * 不依赖 AST —— 误报不影响主路径，只决定验证脚本的强度。
     */
    boolean containsSpringBootMarker(Path projectRoot) {
        Path src = projectRoot.resolve("src").resolve("main").resolve("java");
        if (!Files.exists(src)) return false;
        try (var walk = Files.walk(src)) {
            return walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .limit(200)
                    .anyMatch(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            return content.contains("SpringBootApplication");
                        } catch (IOException ex) {
                            return false;
                        }
                    });
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * 执行验证命令，把结果落盘到 {@code logPath}，并返回摘要。
     *
     * @param projectRoot 项目根目录（执行命令的工作目录）
     * @param template    命令模板
     * @param logPath     verify.log 的目标文件路径
     */
    public VerifyResult run(Path projectRoot, VerifyCommandTemplate template, Path logPath) {
        return run(projectRoot, template, logPath, DEFAULT_TIMEOUT_SECONDS);
    }

    public VerifyResult run(Path projectRoot, VerifyCommandTemplate template, Path logPath, long timeoutSeconds) {
        if (template == null || template.getCommands() == null || template.getCommands().isEmpty()) {
            return VerifyResult.builder()
                    .exitCode(-1)
                    .command("")
                    .logTail("empty command template")
                    .build();
        }

        // 多命令用 && 串成一个 shell 命令（兼容 bash / PowerShell 行为在 Windows 通过 /bin/sh）
        String command = String.join(" && ", template.getCommands());
        Instant started = Instant.now();
        StringBuilder output = new StringBuilder();
        int exit = -1;

        try {
            ProcessBuilder pb = shellProcess(command);
            if (projectRoot != null && Files.exists(projectRoot)) {
                pb.directory(projectRoot.toFile());
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int truncatedNoticeShown = 0;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_LOG_BYTES) {
                        String clipped = line.length() > MAX_LINE_BYTES
                                ? line.substring(0, MAX_LINE_BYTES) + "...(line clipped)"
                                : line;
                        output.append(clipped).append('\n');
                    } else if (truncatedNoticeShown == 0) {
                        output.append("\n…(verify output truncated at ").append(MAX_LOG_BYTES).append(" bytes)\n");
                        truncatedNoticeShown = 1;
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.append("\n[verify-runner] forcibly terminated after ")
                        .append(timeoutSeconds).append("s\n");
            } else {
                exit = process.exitValue();
            }
        } catch (IOException ex) {
            output.append("\n[verify-runner] IO error: ").append(ex.getMessage()).append('\n');
            log.warn("verify IO error: {}", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            output.append("\n[verify-runner] interrupted\n");
        }

        Instant finished = Instant.now();
        long durationMs = Duration.between(started, finished).toMillis();

        // 落盘 verify.log
        try {
            if (logPath != null) {
                if (logPath.getParent() != null) Files.createDirectories(logPath.getParent());
                Files.writeString(logPath, output.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            log.warn("verify.log write failed: {}", ex.getMessage());
        }

        // logTail —— 末 60 行
        String tail = tailLines(output.toString(), 60);

        return VerifyResult.builder()
                .exitCode(exit)
                .durationMs(durationMs)
                .command(command)
                .logTail(tail)
                .startedAt(started)
                .finishedAt(finished)
                .build();
    }

    /** 按操作系统选择 shell：Windows 走 {@code cmd.exe /c}，其余走 {@code /bin/sh -c}。 */
    private static ProcessBuilder shellProcess(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("/bin/sh", "-c", command);
    }

    private static String tailLines(String text, int n) {        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length <= n) return text;
        StringBuilder sb = new StringBuilder();
        sb.append("…(省略 ").append(lines.length - n).append(" 行 head)…\n");
        for (int i = lines.length - n; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
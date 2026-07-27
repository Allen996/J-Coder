package org.example.agent.core.task.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VerifyRunner 自适应命令模板与执行结果测试（part5 §8.7）。
 */
class VerifyRunnerTest {

    private final VerifyRunner runner = new VerifyRunner();

    @Test
    @DisplayName("Spring Boot 项目 → MAVEN_COMPILE_TEST_COMPILE 模板")
    void detect_springBootProject(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("src").resolve("main").resolve("java").resolve("com.example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("App.java"),
                "@org.springframework.boot.autoconfigure.SpringBootApplication\n" +
                "public class App {}\n",
                StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>",
                StandardCharsets.UTF_8);

        VerifyCommandTemplate template = runner.detect(tmp);
        assertEquals(VerifyCommandTemplate.Kind.MAVEN_COMPILE_TEST_COMPILE, template.getKind());
        assertTrue(template.renderCommand().contains("test-compile"));
        assertNotEquals("mvn -q compile && mvn -q test", template.renderCommand());
    }

    @Test
    @DisplayName("普通 Maven 项目 → MAVEN_COMPILE_TEST 模板")
    void detect_plainMaven(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion></project>",
                StandardCharsets.UTF_8);
        VerifyCommandTemplate template = runner.detect(tmp);
        assertEquals(VerifyCommandTemplate.Kind.MAVEN_COMPILE_TEST, template.getKind());
        assertTrue(template.renderCommand().contains("mvn -q test"));
    }

    @Test
    @DisplayName("Gradle 项目 → GRADLE 模板")
    void detect_gradleProject(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("build.gradle"), "// gradle", StandardCharsets.UTF_8);
        VerifyCommandTemplate template = runner.detect(tmp);
        assertEquals(VerifyCommandTemplate.Kind.GRADLE, template.getKind());
        assertTrue(template.renderCommand().contains("gradlew"));
    }

    @Test
    @DisplayName("npm 项目 → NPM_TEST 模板")
    void detect_npmProject(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("package.json"), "{}", StandardCharsets.UTF_8);
        VerifyCommandTemplate template = runner.detect(tmp);
        assertEquals(VerifyCommandTemplate.Kind.NPM_TEST, template.getKind());
        assertTrue(template.renderCommand().contains("npm"));
    }

    @Test
    @DisplayName("未识别 → GENERIC_BUILD 模板")
    void detect_unknownProject(@TempDir Path tmp) {
        VerifyCommandTemplate template = runner.detect(tmp);
        assertEquals(VerifyCommandTemplate.Kind.GENERIC_BUILD, template.getKind());
    }

    @Test
    @DisplayName("detect(null) 返回 GENERIC_BUILD 不抛异常")
    void detect_nullProject() {
        VerifyCommandTemplate template = runner.detect(null);
        assertEquals(VerifyCommandTemplate.Kind.GENERIC_BUILD, template.getKind());
    }

    @Test
    @DisplayName("run 落盘 verify.log 且 exitCode 反映真实状态")
    void run_executesCommandAndWritesLog(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("hello.sh"),
                "#!/bin/sh\necho hello\nexit 0\n",
                StandardCharsets.UTF_8);
        VerifyCommandTemplate template = VerifyCommandTemplate.builder()
                .kind(VerifyCommandTemplate.Kind.GENERIC_BUILD)
                .commands(List.of("sh hello.sh"))
                .description("test")
                .build();

        Path log = tmp.resolve("verify.log");
        VerifyResult result = runner.run(tmp, template, log, 30L);

        assertTrue(result.passed(), "exit 0 should pass; tail=" + result.getLogTail());
        assertEquals(0, result.getExitCode());
        assertTrue(Files.exists(log));
        String content = Files.readString(log);
        assertTrue(content.contains("hello"));
    }

    @Test
    @DisplayName("run 非零退出码 → VerifyResult.passed() == false")
    void run_nonzeroExitFails(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("fail.sh"),
                "#!/bin/sh\necho fail >&2\nexit 7\n",
                StandardCharsets.UTF_8);
        VerifyCommandTemplate template = VerifyCommandTemplate.builder()
                .kind(VerifyCommandTemplate.Kind.GENERIC_BUILD)
                .commands(List.of("sh fail.sh"))
                .build();
        VerifyResult result = runner.run(tmp, template, tmp.resolve("verify.log"), 30L);
        assertFalse(result.passed());
        assertEquals(7, result.getExitCode());
        assertTrue(result.getLogTail().contains("fail"));
    }
}
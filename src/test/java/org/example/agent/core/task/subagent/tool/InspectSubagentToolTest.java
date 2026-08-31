package org.example.agent.core.task.subagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.context.session.SessionMessageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InspectSubagentTool 阶段 3 测试。
 *
 * 验证：
 *  - scope 默认 summary
 *  - 不存在的 session 返回 [empty]
 *  - 非法 scope 返回 [error]
 *  - mid-term 渲染正确
 *  - 空 taskId 返回 [error]
 */
class InspectSubagentToolTest {

    @TempDir
    Path tempDir;

    SessionMessageStore sessionStore;
    InspectSubagentTool tool;

    @BeforeEach
    void setUp() throws Exception {
        Path sessionsRoot = tempDir.resolve(".agent").resolve("sessions");
        Files.createDirectories(sessionsRoot);
        sessionStore = new SessionMessageStore();
        ReflectionTestUtils.setField(sessionStore, "sessionsRoot", sessionsRoot);
        tool = new InspectSubagentTool(sessionStore, new ObjectMapper());
    }

    @Test
    void emptyTaskIdReturnsError() {
        String r = tool.inspectSubagent("", "summary");
        assertTrue(r.contains("[error]"), "empty taskId should error; got: " + r);
    }

    @Test
    void invalidScopeReturnsError() {
        String r = tool.inspectSubagent("st-1", "garbage");
        assertTrue(r.contains("[error]") && r.contains("invalid scope"),
                "invalid scope should error; got: " + r);
    }

    @Test
    void missingSessionReturnsEmpty() {
        String r = tool.inspectSubagent("nonexistent", "summary");
        assertTrue(r.contains("[empty]"), "missing session should be empty; got: " + r);
    }

    @Test
    void summaryDefaultsWhenScopeNull() throws Exception {
        Path sessionDir = sessionsRootDir("st-2");
        Files.createDirectories(sessionDir);
        // dag-state.json 含 runtime entry
        Files.writeString(sessionDir.resolve("dag-state.json"),
                "{\"planId\":\"p\",\"sessionId\":\"st-2\",\"status\":\"RUNNING\",\"runtime\":{" +
                "\"st-2\":{\"state\":\"COMPLETED\",\"attempts\":1,\"lastResult\":{" +
                "\"status\":\"COMPLETED\",\"report\":\"ok\",\"reason\":\"\",\"durationMs\":100}}}}");

        String r = tool.inspectSubagent("st-2", null);
        assertTrue(r.contains("== summary =="), "default scope should be summary; got: " + r);
        assertTrue(r.contains("state: COMPLETED"));
    }

    @Test
    void midTermRenders() throws Exception {
        Path sessionDir = sessionsRootDir("st-3");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("mid-term.json"),
                "[{\"role\":\"user\",\"content\":\"hello\"}," +
                "{\"role\":\"assistant\",\"content\":\"hi\"}]");

        String r = tool.inspectSubagent("st-3", "mid-term");
        assertTrue(r.contains("== mid-term =="));
        assertTrue(r.contains("hello"));
        assertTrue(r.contains("hi"));
    }

    @Test
    void allScopeConcatenates() throws Exception {
        Path sessionDir = sessionsRootDir("st-4");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("dag-state.json"),
                "{\"planId\":\"p\",\"sessionId\":\"st-4\",\"status\":\"RUNNING\",\"runtime\":{" +
                "\"st-4\":{\"state\":\"COMPLETED\",\"attempts\":1}}}");
        Files.writeString(sessionDir.resolve("mid-term.json"),
                "[{\"role\":\"user\",\"content\":\"x\"}]");

        String r = tool.inspectSubagent("st-4", "all");
        assertTrue(r.contains("== summary =="));
        assertTrue(r.contains("== mid-term =="));
        assertTrue(r.contains("== tool-calls =="));
    }

    private Path sessionsRootDir(String taskId) {
        return ((Path) ReflectionTestUtils.getField(sessionStore, "sessionsRoot")).resolve(taskId);
    }
}
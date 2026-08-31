package org.example.agent.context.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SessionCompressor 单元测试（阶段 3）。
 *
 * 验证：
 *  - 幂等性 —— short-term 不存在时 noop
 *  - 正常压缩 —— short-term 折叠进 mid-term + short-term 被删
 *  - 多次压缩 —— 第二次调用 short-term 已被删 → noop
 */
class SessionCompressorTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void shortTermMissingIsNoop() {
        SessionCompressor compressor = new SessionCompressor();
        boolean compressed = compressor.compressIfPresent(tempDir, mapper);
        assertFalse(compressed, "should be noop when short-term.json missing");
    }

    @Test
    void compressFoldsShortTermIntoMidTerm() throws Exception {
        // 准备 short-term.json
        Path sessionDir = tempDir.resolve("st-1");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("short-term.json"),
                "[{\"role\":\"user\",\"content\":\"hello\"}," +
                "{\"role\":\"assistant\",\"content\":\"world\"}]",
                StandardCharsets.UTF_8);

        SessionCompressor compressor = new SessionCompressor();
        boolean compressed = compressor.compressIfPresent(sessionDir, mapper);
        assertTrue(compressed, "should compress");

        // short-term 应被删
        assertFalse(Files.exists(sessionDir.resolve("short-term.json")),
                "short-term.json should be deleted after compression");

        // mid-term.json 应有内容(首条是 [meta] subagent_compress)
        assertTrue(Files.exists(sessionDir.resolve("mid-term.json")));
        String raw = Files.readString(sessionDir.resolve("mid-term.json"), StandardCharsets.UTF_8);
        assertTrue(raw.contains("subagent_compress"),
                "mid-term should have subagent_compress meta tag");
        assertTrue(raw.contains("hello"), "mid-term should contain original user message");
        assertTrue(raw.contains("world"), "mid-term should contain original assistant message");
    }

    @Test
    void compressPreservesExistingMidTerm() throws Exception {
        Path sessionDir = tempDir.resolve("st-2");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("mid-term.json"),
                "[{\"role\":\"system\",\"content\":\"sysprompt\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(sessionDir.resolve("short-term.json"),
                "[{\"role\":\"user\",\"content\":\"hi\"}]",
                StandardCharsets.UTF_8);

        SessionCompressor compressor = new SessionCompressor();
        boolean compressed = compressor.compressIfPresent(sessionDir, mapper);
        assertTrue(compressed);

        String mid = Files.readString(sessionDir.resolve("mid-term.json"), StandardCharsets.UTF_8);
        assertTrue(mid.contains("sysprompt"), "old mid-term should be preserved");
        assertTrue(mid.contains("hi"), "short-term folded in");
        assertTrue(mid.contains("subagent_compress"));
    }

    @Test
    void secondCompressIsIdempotent() throws Exception {
        Path sessionDir = tempDir.resolve("st-3");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("short-term.json"),
                "[{\"role\":\"user\",\"content\":\"once\"}]",
                StandardCharsets.UTF_8);

        SessionCompressor compressor = new SessionCompressor();
        assertTrue(compressor.compressIfPresent(sessionDir, mapper));
        // 第二次:short-term 已被删 → noop
        assertFalse(compressor.compressIfPresent(sessionDir, mapper),
                "second call should be noop");
    }

    @Test
    void nullSessionDirIsSafe() {
        SessionCompressor compressor = new SessionCompressor();
        assertFalse(compressor.compressIfPresent(null, mapper));
    }

    @Test
    void shortTermWithMemoryFileEnvelope() throws Exception {
        // 模拟 SessionMessageStore 写的 envelope 格式:{ schema, kind, sessionId, ..., messages: [...] }
        Path sessionDir = tempDir.resolve("st-4");
        Files.createDirectories(sessionDir);
        String envelope = "{\"schema\":\"3\",\"kind\":\"short-term\",\"sessionId\":\"st-4\"," +
                "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        Files.writeString(sessionDir.resolve("short-term.json"), envelope, StandardCharsets.UTF_8);

        SessionCompressor compressor = new SessionCompressor();
        assertTrue(compressor.compressIfPresent(sessionDir, mapper));

        String mid = Files.readString(sessionDir.resolve("mid-term.json"), StandardCharsets.UTF_8);
        assertNotNull(mid);
        assertTrue(mid.contains("hi"));
    }
}
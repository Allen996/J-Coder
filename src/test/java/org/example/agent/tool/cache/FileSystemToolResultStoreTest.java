package org.example.agent.tool.cache;

import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.spi.ToolDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemToolResultStoreTest {

    @TempDir
    Path tmp;

    private FileSystemToolResultStore store;

    @BeforeEach
    void setUp() throws Exception {
        // 通过反射设置 baseDir 到 @TempDir,因为构造器从 cli.project-root 读
        store = new FileSystemToolResultStore(tmp.toString(),
                new CliToolProperties());
        var init = FileSystemToolResultStore.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(store);
    }

    private ToolDescriptor readFileDescriptor() {
        return ToolDescriptor.low("read_file", "test").withTimeout(10_000L);
    }

    private String saveSample(String tool, Map<String, Object> args, String result, Path watched) throws Exception {
        Files.writeString(watched, "hello world");
        // 直接调 save,descriptor 提供 cacheable/readonly
        ToolDescriptor desc = ToolDescriptor.low(tool, "test").withTimeout(10_000L);
        return store.save(tool, args, result, "exec-test", desc);
    }

    @Test
    @DisplayName("save + recall 全量,内容完全一致")
    void saveAndRecallFull() {
        String id = store.save("read_file",
                Map.of("path", tmp.resolve("foo.txt").toString()),
                "1\thello\n2\tworld", "exec-1", readFileDescriptor());
        assertNotNull(id);
        assertTrue(id.matches("^[a-z0-9]{8}$"));

        var result = store.recall(id, null, null, null);
        var ok = assertInstanceOf(ToolResultStore.RecallResult.Ok.class, result);
        assertEquals("hello\nworld", ok.content(), "recall 时剥离行号");
    }

    @Test
    @DisplayName("recall 行范围过滤:返回范围内行,不带行号")
    void recallLineRange() {
        String content = "1\tL1\n2\tL2\n3\tL3\n4\tL4";
        String id = store.save("read_file", Map.of("path", "x"), content, "e", readFileDescriptor());

        var result = store.recall(id, 2, 3, null);
        var ok = assertInstanceOf(ToolResultStore.RecallResult.Ok.class, result);
        assertEquals("L2\nL3", ok.content());
    }

    @Test
    @DisplayName("recall 正则过滤:返回匹配行 ±2 行 context,最多 100 行,不带行号")
    void recallRegexFilter() {
        String content = "1\tL1\n2\tL2\n3\tL3\n4\tL4\n5\tL5\n6\tL6";
        String id = store.save("read_file", Map.of("path", "x"), content, "e", readFileDescriptor());

        var result = store.recall(id, null, null, "L3");
        var ok = assertInstanceOf(ToolResultStore.RecallResult.Ok.class, result);
        // 匹配行 L3 + 上下 2 行 L1/L2/L4/L5 → 但已有行号剥离
        assertTrue(ok.content().contains("L3"), "含匹配行");
        assertTrue(ok.content().contains("L2"), "含上一行 context");
        assertTrue(ok.content().contains("L4"), "含下一行 context");
        assertFalse(ok.content().contains("L1\t"), "不应有 L1 —— 超过 ±2 行");
        assertFalse(ok.content().contains("L5\t"), "不应有 L5 —— 超过 ±2 行");
        assertFalse(ok.content().matches("(?s).*\\d+\\t.*"), "返回结果不应含行号前缀");
    }

    @Test
    @DisplayName("recall 非法 id → InvalidId")
    void recallInvalidId() {
        var r1 = store.recall("../../etc/passwd", null, null, null);
        assertInstanceOf(ToolResultStore.RecallResult.InvalidId.class, r1);

        var r2 = store.recall("ABC12345", null, null, null);
        assertInstanceOf(ToolResultStore.RecallResult.InvalidId.class, r2);

        var r3 = store.recall("abc123", null, null, null);   // 6 位,长度不够
        assertInstanceOf(ToolResultStore.RecallResult.InvalidId.class, r3);
    }

    @Test
    @DisplayName("recall 不存在 id → NotFound")
    void recallNotFound() {
        var result = store.recall("zzzzzzzz", null, null, null);
        assertInstanceOf(ToolResultStore.RecallResult.NotFound.class, result);
    }

    @Test
    @DisplayName("recall 已过期 → Expired 且文件被删除")
    void recallExpired() throws Exception {
        // 手工构造一个 expiresAt 已过的 record 文件
        Path file = store.getBaseDir().resolve("tr-expire01.json");
        String json = """
                {"id":"expire01","toolName":"read_file","toolArgs":{"path":"x"},
                 "result":"old","capturedAt":"2020-01-01T00:00:00Z",
                 "expiresAt":"2020-01-02T00:00:00Z","resultSizeBytes":3,
                 "watchedPaths":[],"watchedHashes":[]}""";
        Files.writeString(file, json);

        var result = store.recall("expire01", null, null, null);
        assertInstanceOf(ToolResultStore.RecallResult.Expired.class, result);
        assertFalse(Files.exists(file), "过期记录应被删除");
    }

    @Test
    @DisplayName("recall watchedPath 文件变更 → StaleRemoved 且记录删除")
    void recallStaleRemoved() throws Exception {
        Path watched = tmp.resolve("watched.txt");
        Files.writeString(watched, "v1");
        // save 记录 v1 的 mtime
        String id = store.save("read_file",
                Map.of("path", watched.toString()),
                "data", "e", readFileDescriptor());

        // 修改 watched → mtime 变
        Files.writeString(watched, "v2 - changed");

        var result = store.recall(id, null, null, null);
        assertInstanceOf(ToolResultStore.RecallResult.StaleRemoved.class, result);
        assertFalse(Files.exists(store.getBaseDir().resolve("tr-" + id + ".json")),
                "stale 记录应被删除");
    }

    @Test
    @DisplayName("invalidateByPath 删所有 watchedPaths 包含该路径的记录")
    void invalidateByPath() throws Exception {
        Path fileA = tmp.resolve("a.txt");
        Path fileB = tmp.resolve("b.txt");
        Files.writeString(fileA, "A");
        Files.writeString(fileB, "B");

        String idA = store.save("read_file", Map.of("path", fileA.toString()), "A data", "e", readFileDescriptor());
        String idB = store.save("read_file", Map.of("path", fileB.toString()), "B data", "e", readFileDescriptor());
        String idShell = store.save("run_shell", Map.of("cmd", "ls"), "shell out", "e",
                ToolDescriptor.high("run_shell", "shell").withTimeout(5000L));

        store.invalidateByPath(fileA);

        assertFalse(Files.exists(store.getBaseDir().resolve("tr-" + idA + ".json")), "A 应被失效");
        assertTrue(Files.exists(store.getBaseDir().resolve("tr-" + idB + ".json")), "B 不受影响");
        assertTrue(Files.exists(store.getBaseDir().resolve("tr-" + idShell + ".json")), "shell 不受影响");
    }

    @Test
    @DisplayName("scanIds 文本中的 #xxxxx 全部识别,顺序保留,去重")
    void scanIdsFromText() {
        String text = "看一下 #abc12345 和 #def67890,以及 #abc12345(重复)";
        List<String> ids = store.scanIds(text);
        assertEquals(List.of("abc12345", "def67890"), ids);
    }

    @Test
    @DisplayName("metadataHint 返回 id + toolName + size,不包含 result 内容")
    void metadataHintShape() {
        String id = store.save("read_file",
                Map.of("path", tmp.resolve("foo.txt").toString()),
                "secret-content-not-shown", "e", readFileDescriptor());

        String hint = store.metadataHint(id);
        assertTrue(hint.contains("#" + id));
        assertTrue(hint.contains("read_file"));
        assertTrue(hint.contains("B"));
        assertFalse(hint.contains("secret-content"), "hint 不应含 result 内容");
    }
}

package org.example.agent.tool.cache;

import org.example.agent.tool.ToolExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallToolResultTest {

    private RecallToolResult tool;
    private RecordingStore store;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        tool = new RecallToolResult(store);
    }

    @Test
    @DisplayName("合法 id + 无过滤器 → store.recall(id, null, null, null)")
    void passesThroughToStore() {
        store.nextResult = new ToolResultStore.RecallResult.Ok("hello");
        String out = tool.recallToolResult("abc12345", null, null, null);
        assertEquals("hello", out);
        assertEquals(java.util.Arrays.asList("abc12345", null, null, null), store.lastCall);
    }

    @Test
    @DisplayName("startLine + endLine 都传 → 行范围过滤")
    void passesLineRange() {
        store.nextResult = new ToolResultStore.RecallResult.Ok("L2-L5");
        String out = tool.recallToolResult("abc12345", 2, 5, null);
        assertEquals("L2-L5", out);
        assertEquals(java.util.Arrays.asList("abc12345", 2, 5, null), store.lastCall);
    }

    @Test
    @DisplayName("只传 pattern → 正则过滤")
    void passesPattern() {
        store.nextResult = new ToolResultStore.RecallResult.Ok("matches");
        String out = tool.recallToolResult("abc12345", null, null, "TODO");
        assertEquals("matches", out);
        assertEquals(java.util.Arrays.asList("abc12345", null, null, "TODO"), store.lastCall);
    }

    @Test
    @DisplayName("同时传 startLine + pattern → 抛 INVALID_ARGUMENT")
    void rejectsConflictingFilters() {
        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> tool.recallToolResult("abc12345", 1, 5, "TODO"));
        assertEquals("INVALID_ARGUMENT", ex.getErrorCode().name());
        assertTrue(ex.getMessage().contains("互斥"));
    }

    @Test
    @DisplayName("id 含路径穿越 → 抛 INVALID_ARGUMENT,不调 store")
    void rejectsPathTraversalId() {
        assertThrows(ToolExecutionException.class,
                () -> tool.recallToolResult("../../etc/passwd", null, null, null));
        assertThrows(ToolExecutionException.class,
                () -> tool.recallToolResult("UPPERCASE", null, null, null));
        assertThrows(ToolExecutionException.class,
                () -> tool.recallToolResult("short", null, null, null));   // 5 位
        assertEquals(0, store.callCount, "非法 id 不应触发 store.recall");
    }

    @Test
    @DisplayName("id 为 null → 抛 INVALID_ARGUMENT")
    void nullId() {
        assertThrows(ToolExecutionException.class,
                () -> tool.recallToolResult(null, null, null, null));
    }

    @Test
    @DisplayName("store 返回 Expired → 友好提示")
    void expiredTranslatesToFriendlyString() {
        store.nextResult = new ToolResultStore.RecallResult.Expired("abc12345");
        String out = tool.recallToolResult("abc12345", null, null, null);
        assertTrue(out.contains("[EXPIRED]"));
        assertTrue(out.contains("abc12345"));
        assertTrue(out.contains("重新调用"));
    }

    @Test
    @DisplayName("store 返回 StaleRemoved → 友好提示")
    void staleTranslatesToFriendlyString() {
        store.nextResult = new ToolResultStore.RecallResult.StaleRemoved("abc12345");
        String out = tool.recallToolResult("abc12345", null, null, null);
        assertTrue(out.contains("[STALE_AND_REMOVED]"));
        assertTrue(out.contains("已变更"));
    }

    @Test
    @DisplayName("store 返回 NotFound → 友好提示")
    void notFoundTranslatesToFriendlyString() {
        store.nextResult = new ToolResultStore.RecallResult.NotFound("abc12345");
        String out = tool.recallToolResult("abc12345", null, null, null);
        assertTrue(out.contains("[NOT_FOUND]"));
    }

    @Test
    @DisplayName("空字符串 pattern 等同于不传 → 调 store 时传 null")
    void emptyPatternBecomesNull() {
        store.nextResult = new ToolResultStore.RecallResult.Ok("ok");
        tool.recallToolResult("abc12345", null, null, "");
        assertEquals(null, store.lastCall.get(3), "空 pattern 应规范化为 null");
    }

    /** 最小 mock —— 只录最后一次调用 + 返回预设结果。 */
    static class RecordingStore implements ToolResultStore {
        ToolResultStore.RecallResult nextResult;
        List<Object> lastCall;
        int callCount = 0;

        @Override
        public String save(String toolName, Map<String, Object> args, String result,
                           String executionId, org.example.agent.tool.spi.ToolDescriptor descriptor) {
            return null;
        }

        @Override
        public RecallResult recall(String id, Integer startLine, Integer endLine, String pattern) {
            callCount++;
            lastCall = java.util.Arrays.asList(id, startLine, endLine, pattern);
            return nextResult;
        }

        @Override
        public void invalidateByPath(java.nio.file.Path path) { }

        @Override
        public List<String> scanIds(String text) { return List.of(); }

        @Override
        public String metadataHint(String id) { return "#" + id; }

        @Override
        public void evictIfOverBudget() { }
    }
}

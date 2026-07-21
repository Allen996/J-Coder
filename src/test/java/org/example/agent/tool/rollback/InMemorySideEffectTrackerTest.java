package org.example.agent.tool.rollback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InMemorySideEffectTracker 的 smoke test。
 *
 * <p>覆盖面:
 * <ul>
 *   <li>pre-state 快照 + restore 双向一致</li>
 *   <li>写入原本不存在的文件 → rollback 时文件被删</li>
 *   <li>rollbackAll 倒序执行(后入先出)</li>
 *   <li>未 bind 时 recordFileChange 是 no-op</li>
 *   <li>clear() 后栈被清空</li>
 * </ul>
 */
class InMemorySideEffectTrackerTest {

    @TempDir
    Path tmp;

    InMemorySideEffectTracker tracker;
    static final String EXEC = "exec-rollback-1";

    @BeforeEach
    void setUp() {
        tracker = new InMemorySideEffectTracker();
        tracker.bind(EXEC);
    }

    @AfterEach
    void tearDown() {
        tracker.clear();
    }

    @Test
    @DisplayName("pre-state 快照 + restore 双向一致")
    void rollback_restoresPreState_exactly() throws Exception {
        Path file = tmp.resolve("a.txt");
        Files.writeString(file, "ORIGINAL-CONTENT");

        tracker.recordFileChange("writeFile", file.toString(),
                Files.readAllBytes(file));

        // 模拟工具调用后,内容被改成新内容
        Files.writeString(file, "NEW-CONTENT");
        assertEquals("NEW-CONTENT", Files.readString(file));

        RollbackSummary sum = tracker.rollbackAll();
        assertEquals(1, sum.rolledCount());
        assertEquals(1, sum.rolledTargets().size());
        assertTrue(sum.rolledTargets().get(0).contains("restored 16 bytes"));

        // 内容回到 ORIGINAL-CONTENT
        assertEquals("ORIGINAL-CONTENT", Files.readString(file));
    }

    @Test
    @DisplayName("pre-state 为 null 时 rollback 执行 delete")
    void rollback_deletesFileCreatedByTool() throws Exception {
        Path file = tmp.resolve("created-by-tool.txt");
        assertFalse(Files.exists(file));

        // 工具创建了新文件(原本不存在)
        Files.writeString(file, "new content");
        tracker.recordFileChange("writeFile", file.toString(), null);

        RollbackSummary sum = tracker.rollbackAll();
        assertEquals(1, sum.rolledCount());
        assertFalse(Files.exists(file), "rollback 必须删除 pre-state 不存在的文件");
        assertTrue(sum.rolledTargets().get(0).contains("deleted"));
    }

    @Test
    @DisplayName("rollbackAll 按后入先出顺序执行")
    void rollback_reversesOrder() throws Exception {
        Path a = tmp.resolve("a.txt");
        Path b = tmp.resolve("b.txt");
        Files.writeString(a, "A-original");
        Files.writeString(b, "B-original");

        // 模拟按顺序: write A → write B
        tracker.recordFileChange("writeFile", a.toString(),
                Files.readAllBytes(a));
        Files.writeString(a, "A-new");

        tracker.recordFileChange("writeFile", b.toString(),
                Files.readAllBytes(b));
        Files.writeString(b, "B-new");

        RollbackSummary sum = tracker.rollbackAll();
        assertEquals(2, sum.rolledCount());
        // 顺序: b 先回滚,然后 a
        assertEquals(List.of("B-original", "A-original"),
                List.of(Files.readString(b), Files.readString(a)));
    }

    @Test
    @DisplayName("未 bind 时 recordFileChange 是 no-op,不抛")
    void recordFileChange_withoutBind_silentNoOp() throws Exception {
        InMemorySideEffectTracker fresh = new InMemorySideEffectTracker();
        // 不调 bind
        fresh.recordFileChange("writeFile", "/tmp/whatever", null);
        fresh.recordFileChange("editFile", "/tmp/another", new byte[]{1, 2, 3});
        RollbackSummary sum = fresh.rollbackAll();
        assertEquals(0, sum.rolledCount());
        assertTrue(sum.wasEmpty());
    }

    @Test
    @DisplayName("clear() 后栈被清空,roll back 是 no-op")
    void clear_emptiesStack() throws Exception {
        Path file = tmp.resolve("c.txt");
        Files.writeString(file, "ORIG");
        tracker.recordFileChange("writeFile", file.toString(),
                Files.readAllBytes(file));
        Files.writeString(file, "NEW");

        tracker.clear();
        // clear 后再 bind 同一个 executionId,栈应当是空的新栈
        tracker.bind(EXEC);
        RollbackSummary sum = tracker.rollbackAll();
        assertTrue(sum.wasEmpty());
        // 文件未被改回,因为栈已被清理
        assertEquals("NEW", Files.readString(file));
    }

    @Test
    @DisplayName("render() 输出可读多行,无 entries 时给 (none tracked)")
    void render_isHumanReadable() throws Exception {
        Path f = tmp.resolve("d.txt");
        Files.writeString(f, "ORIG");
        tracker.recordFileChange("editFile", f.toString(),
                Files.readString(f).getBytes(StandardCharsets.UTF_8));
        Files.writeString(f, "NEW");

        RollbackSummary sum = tracker.rollbackAll();
        String rendered = sum.render();
        assertTrue(rendered.startsWith("  - editFile: "),
                "每条以两空格-缩进开头; actual=" + rendered);
        assertTrue(rendered.contains("(restored 4 bytes)"));
    }
}

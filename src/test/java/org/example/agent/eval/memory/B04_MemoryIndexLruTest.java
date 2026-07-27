package org.example.agent.eval.memory;

import org.example.agent.context.memory.MemoryIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度 B KPI：MEMORY.md LRU 20 + 幂等 + 持久化。
 * 对应 TEST.md §5.1 "B 维度 / B04 容量守恒"。
 */
class B04_MemoryIndexLruTest {

    @Test
    void cacheIsBoundedByLruCapacity(@TempDir Path tmp) {
        Path memPath = tmp.resolve("MEMORY.md");
        MemoryIndex index = new MemoryIndex(memPath);

        // 默认 LRU = 20；连续 add 25 条
        for (int i = 0; i < 25; i++) {
            index.add("path_" + i, "summary " + i);
        }

        // KPI 1：cache.size == 20
        assertThat(index.loadOrEmpty())
                .as("cache must be bounded by LRU capacity (20)")
                .hasSize(20);

        // KPI 2：最早的 5 条（path_0..4）被淘汰
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            assertThat(index.loadOrEmpty())
                    .as("path_%d should be evicted by LRU", idx)
                    .noneMatch(e -> e.getPath().equals("path_" + idx));
        }

        // 最新的 20 条保留（path_5..24）
        for (int i = 5; i < 25; i++) {
            final int idx = i;
            assertThat(index.loadOrEmpty())
                    .as("path_%d should still be present", idx)
                    .anyMatch(e -> e.getPath().equals("path_" + idx));
        }

        // 持久化：MEMORY.md 落盘存在
        assertThat(Files.exists(memPath))
                .as("MEMORY.md must be persisted on disk")
                .isTrue();
    }

    @Test
    void addingSamePathIsIdempotent(@TempDir Path tmp) {
        Path memPath = tmp.resolve("MEMORY.md");
        MemoryIndex index = new MemoryIndex(memPath);

        index.add("Nico.md", "项目骨架");
        index.add("Nico.md", "项目骨架 (updated)");
        index.add("Nico.md", "项目骨架 (updated again)");

        // 幂等：同一 path 多次 add 不重复
        assertThat(index.loadOrEmpty())
                .as("same path added 3 times must collapse to 1 entry")
                .hasSize(1);

        // 最新 summary 应是最后一次写入的值
        assertThat(index.loadOrEmpty().get(0).getSummary())
                .isEqualTo("项目骨架 (updated again)");
    }

    @Test
    void reloadAfterRestartPreservesEntries(@TempDir Path tmp) {
        Path memPath = tmp.resolve("MEMORY.md");

        MemoryIndex first = new MemoryIndex(memPath);
        first.add("a.md", "first");
        first.add("b.md", "second");
        first.add("c.md", "third");

        // 模拟"关闭再打开"
        MemoryIndex second = new MemoryIndex(memPath);
        second.reload();

        // LRU 头插 → 新的在前
        assertThat(second.loadOrEmpty())
                .hasSize(3)
                .extracting(MemoryIndex.IndexEntry::getPath)
                .containsExactly("c.md", "b.md", "a.md");
    }

    @Test
    void lruEvictionPreservesDiskFiles(@TempDir Path tmp) throws Exception {
        // §3 B04 副断言：被 LRU 淘汰的条目，磁盘 .md 文件**不**被删除（仅从索引移除）
        Path memPath = tmp.resolve("MEMORY.md");
        MemoryIndex index = new MemoryIndex(memPath);

        for (int i = 0; i < 25; i++) {
            Path f = tmp.resolve("file_" + i + ".md");
            Files.writeString(f, "content " + i);
            index.add(f.toString(), "summary " + i);
        }

        // 25 个磁盘文件都还在
        for (int i = 0; i < 25; i++) {
            assertThat(Files.exists(tmp.resolve("file_" + i + ".md")))
                    .as("file_%d.md should NOT be deleted by LRU eviction", i)
                    .isTrue();
        }
    }
}
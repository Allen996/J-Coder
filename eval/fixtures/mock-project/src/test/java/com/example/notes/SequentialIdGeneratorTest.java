package com.example.notes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SequentialIdGeneratorTest {

    private SequentialIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SequentialIdGenerator();
    }

    @Test
    void firstIdIsOne() {
        long id = generator.nextId();
        assertEquals(1L, id);
    }

    @Test
    void idsAreSequential() {
        long id1 = generator.nextId();
        long id2 = generator.nextId();
        long id3 = generator.nextId();
        assertEquals(1L, id1);
        assertEquals(2L, id2);
        assertEquals(3L, id3);
    }

    @Test
    void idsAreUnique() {
        long id1 = generator.nextId();
        long id2 = generator.nextId();
        assertTrue(id1 != id2, "Generated IDs should be unique");
    }

    @Test
    void generatesPositiveIds() {
        for (int i = 0; i < 100; i++) {
            long id = generator.nextId();
            assertTrue(id > 0, "ID should be positive: " + id);
        }
    }

    @Test
    void concurrentIdGenerationProducesUniqueIds() throws InterruptedException {
        int threadCount = 10;
        int idsPerThread = 100;
        java.util.Set<Long> allIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < idsPerThread; i++) {
                        allIds.add(generator.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertEquals(threadCount * idsPerThread, allIds.size(),
                "All generated IDs should be unique under concurrent access");
    }

    @Test
    void largeNumberOfIdsRemainsUnique() {
        // 失败路径：生成大量 ID 验证不会溢出或重复
        java.util.Set<Long> ids = new java.util.HashSet<>();
        int count = 10000;
        for (int i = 0; i < count; i++) {
            ids.add(generator.nextId());
        }
        assertEquals(count, ids.size(),
                "All " + count + " generated IDs should be unique");
    }
}

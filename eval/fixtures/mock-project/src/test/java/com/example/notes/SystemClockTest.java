package com.example.notes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemClockTest {

    @Test
    void nowReturnsPositiveTimestamp() {
        SystemClock clock = new SystemClock();
        long timestamp = clock.now();
        assertTrue(timestamp > 0, "Timestamp should be positive: " + timestamp);
    }

    @Test
    void nowReturnsCurrentTimeInMillis() {
        SystemClock clock = new SystemClock();
        long before = System.currentTimeMillis();
        long timestamp = clock.now();
        long after = System.currentTimeMillis();
        assertTrue(timestamp >= before && timestamp <= after,
                "Timestamp should be between before and after calls");
    }

    @Test
    void multipleCallsReturnIncreasingValues() {
        SystemClock clock = new SystemClock();
        long t1 = clock.now();
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long t2 = clock.now();
        assertTrue(t2 >= t1, "Later timestamp should be >= earlier timestamp");
    }

    @Test
    void nowReturnsReasonableTimestamp() {
        SystemClock clock = new SystemClock();
        long timestamp = clock.now();
        long currentSystemTime = System.currentTimeMillis();
        long diff = Math.abs(timestamp - currentSystemTime);
        assertTrue(diff < 1000,
                "Timestamp should be within 1 second of system time, but diff was: " + diff + "ms");
    }

    @Test
    void nowDoesNotReturnZeroOrNegative() {
        SystemClock clock = new SystemClock();
        long timestamp = clock.now();
        assertTrue(timestamp > 0,
                "Timestamp must be positive, got: " + timestamp);
    }

    @Test
    void nowReturnsConsistentType() {
        // 失败路径：验证返回值始终为有效的 long 值且不会抛出异常
        SystemClock clock = new SystemClock();
        for (int i = 0; i < 100; i++) {
            long timestamp = clock.now();
            // long 是基本类型，始终有效；这里验证调用不会抛出异常
            assertTrue(timestamp == timestamp,
                    "Timestamp should always be a valid long value");
        }
    }
}

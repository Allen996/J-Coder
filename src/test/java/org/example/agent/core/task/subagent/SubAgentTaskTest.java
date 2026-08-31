package org.example.agent.core.task.subagent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SubAgentTask / SubAgentResult record 校验（阶段 1）。
 */
class SubAgentTaskTest {

    @Test
    void taskIdIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentTask(null, "title", "desc", "out", List.of(), "", "", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentTask("", "title", "desc", "out", List.of(), "", "", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentTask("  ", "title", "desc", "out", List.of(), "", "", 1000));
    }

    @Test
    void defaultsAreApplied() {
        SubAgentTask t = new SubAgentTask("st-1", "t", null, null, null, null, null, 0);
        assertNotNull(t);
        assertEquals("", t.description());
        assertEquals("", t.expectedOutput());
        assertEquals(List.of(), t.contextFiles());
        assertEquals("", t.parentSessionId());
        assertEquals("", t.parentCheckpointId());
        // timeoutMs <= 0 → 默认 10 分钟
        assertEquals(10 * 60 * 1000L, t.timeoutMs());
    }

    @Test
    void explicitTimeoutPreserved() {
        SubAgentTask t = new SubAgentTask("st-1", "t", "d", "o", List.of("a.txt"), "p", "c", 3000);
        assertEquals(3000, t.timeoutMs());
        assertEquals(List.of("a.txt"), t.contextFiles());
        assertEquals("p", t.parentSessionId());
        assertEquals("c", t.parentCheckpointId());
    }

    @Test
    void resultRequiresTaskId() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentResult(null, SubAgentStatus.COMPLETED, "", "", List.of(), List.of(), 0, ""));
    }

    @Test
    void resultSuccessReflectsStatus() {
        SubAgentResult ok = new SubAgentResult("st-1", SubAgentStatus.COMPLETED, "report", "",
                List.of(), List.of(), 100, "/path");
        assertEquals("report", ok.report());
        assertEquals(SubAgentStatus.COMPLETED, ok.status());
        org.junit.jupiter.api.Assertions.assertTrue(ok.isSuccess());

        SubAgentResult failed = new SubAgentResult("st-1", SubAgentStatus.FAILED, "", "boom",
                List.of(), List.of(), 100, "/path");
        org.junit.jupiter.api.Assertions.assertFalse(failed.isSuccess());
    }
}
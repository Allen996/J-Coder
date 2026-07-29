package org.example.cli.renderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusLineTest {

    @Test
    @DisplayName("IDLE 状态下 renderLine 返回空串")
    void idleRenderIsEmpty() {
        StatusLine sl = new StatusLine();
        assertEquals(StatusLine.Phase.IDLE, sl.phase());
        assertEquals("", sl.renderLine(Instant.now()));
        assertNull(sl.elapsed(Instant.now()));
    }

    @Test
    @DisplayName("THINKING 阶段渲染包含秒数")
    void thinkingRender() {
        StatusLine sl = new StatusLine();
        Instant t0 = Instant.now();
        sl.begin(StatusLine.Phase.THINKING, null);
        String s = sl.renderLine(t0);
        assertTrue(s.contains("Thinking..."), "应包含 Thinking...: " + s);
        assertTrue(s.contains("0.0s"), "应包含 0.0s: " + s);
    }

    @Test
    @DisplayName("TOOL_CALLING 阶段带工具名")
    void toolCallingRender() {
        StatusLine sl = new StatusLine();
        Instant t0 = Instant.now();
        sl.begin(StatusLine.Phase.TOOL_CALLING, "read_file");
        Instant later = t0.plusMillis(1500);
        String s = sl.renderLine(later);
        assertTrue(s.contains("Tool_calling..."), "应包含 Tool_calling...: " + s);
        assertTrue(s.contains("read_file"), "应包含 read_file: " + s);
        assertTrue(s.contains("1.5s"), "应包含 1.5s: " + s);
    }

    @Test
    @DisplayName("finish() 后回到 IDLE")
    void finishResets() {
        StatusLine sl = new StatusLine();
        sl.begin(StatusLine.Phase.THINKING, null);
        sl.finish();
        assertEquals(StatusLine.Phase.IDLE, sl.phase());
        assertEquals("", sl.renderLine(Instant.now()));
        assertNull(sl.elapsed(Instant.now()));
    }

    @Test
    @DisplayName("重复 begin 会切换阶段 + 切换 label")
    void repeatedBeginSwitchesPhase() {
        StatusLine sl = new StatusLine();
        Instant t0 = Instant.now();
        sl.begin(StatusLine.Phase.THINKING, null);
        String first = sl.renderLine(t0.plusMillis(5000));
        assertTrue(first.contains("Thinking..."), "首次应显示 Thinking: " + first);

        sl.begin(StatusLine.Phase.TOOL_CALLING, "search");
        String second = sl.renderLine(t0.plusMillis(5500));
        assertTrue(second.contains("Tool_calling..."), "二次应切换到 Tool_calling: " + second);
        assertTrue(second.contains("search"), "应带新 label: " + second);
    }

    @Test
    @DisplayName("工具名过长会被截断")
    void longToolNameTruncated() {
        StatusLine sl = new StatusLine();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append('x');
        sl.begin(StatusLine.Phase.TOOL_CALLING, sb.toString());
        String s = sl.renderLine(Instant.now());
        assertTrue(s.contains("…"), "应包含省略号: " + s);
        // 截断 + 当前秒数 + 一些 ANSI 包装,总长 < 100
        assertTrue(s.length() < 100, "截断后应 < 100 chars: " + s.length());
    }

    @Test
    @DisplayName("elapsedFormat 时间格式化")
    void elapsedFormatTest() {
        assertEquals("0.0s", AnsiStyle.elapsedFormat(Duration.ZERO));
        assertEquals("1.5s", AnsiStyle.elapsedFormat(Duration.ofMillis(1500)));
        assertEquals("59.9s", AnsiStyle.elapsedFormat(Duration.ofMillis(59900)));
        assertEquals("1m00s", AnsiStyle.elapsedFormat(Duration.ofSeconds(60)));
        assertEquals("2m05s", AnsiStyle.elapsedFormat(Duration.ofSeconds(125)));
        assertEquals("1h02m", AnsiStyle.elapsedFormat(Duration.ofSeconds(3725)));
    }
}
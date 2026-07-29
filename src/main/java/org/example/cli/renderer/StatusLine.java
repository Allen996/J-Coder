package org.example.cli.renderer;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 运行时状态行 —— 在 REPL prompt 上方显示"我在干啥 + 耗时"。
 *
 * <p>核心约束（与 CliRenderer 解耦）：
 * <ul>
 *   <li>CliRenderer 在订阅线程触发事件 → 调 {@link #begin} / {@link #finish}</li>
 *   <li>REPL 主线程在 poll 间隙调 {@link #renderLine(Instant)} 拿到当前行内容</li>
 *   <li>状态行写入由调用方负责（REPL 主线程独占 terminal.writer()）</li>
 * </ul>
 *
 * <p>阶段机：IDLE → THINKING → IDLE → TOOL_CALLING → IDLE → ...
 * 同一时刻只有一种阶段。新 begin 自动 finish 上一阶段（覆盖式语义）。
 *
 * <p>线程安全：所有 setter 都是 volatile write；renderLine 是无副作用的快照读取。
 */
@Component
public class StatusLine {

    /** 当前阶段枚举。 */
    public enum Phase {
        IDLE, THINKING, TOOL_CALLING
    }

    private static final int TOOL_NAME_MAX_LEN = 28;

    private volatile Phase phase = Phase.IDLE;
    private volatile Instant startedAt;
    private volatile String label;

    /** 当前阶段。 */
    public Phase phase() {
        return phase;
    }

    /** 距离上次 begin 的实时耗时。如果当前 IDLE，返回 null。 */
    public Duration elapsed(Instant now) {
        Instant s = startedAt;
        if (s == null) return null;
        return Duration.between(s, now);
    }

    /**
     * 进入新阶段。会自动覆盖已有阶段（多次调用 begin 等价于"刷新开始时间"）。
     *
     * @param p     阶段
     * @param label 附加标签（工具名 / 子类型等），可空
     */
    public void begin(Phase p, String label) {
        if (p == null) return;
        this.phase = p;
        this.label = label;
        this.startedAt = Instant.now();
    }

    /**
     * 把阶段重置为 IDLE。已结束的事件（onThought / onObservation / onError 等）都应调用。
     */
    public void finish() {
        this.phase = Phase.IDLE;
        this.startedAt = null;
        this.label = null;
    }

    /**
     * 拿到当前应渲染的状态行 ANSI 字符串。IDLE 时返回空串。
     *
     * @param now 用于计算 elapsed 的"当前时间"，由调用方传入（避免 StatusLine 内部 System call 不一致）
     */
    public String renderLine(Instant now) {
        Phase p = phase;
        if (p == Phase.IDLE) return "";
        Duration d = elapsed(now);
        if (d == null) return "";
        String elapsedStr = AnsiStyle.elapsedFormat(d);
        String content;
        switch (p) {
            case THINKING -> content = String.format("▌ Thinking...(%s)", elapsedStr);
            case TOOL_CALLING -> {
                String tool = AnsiStyle.truncate(label == null ? "?" : label, TOOL_NAME_MAX_LEN);
                content = String.format("▌ Tool_calling...(%s, %s)", tool, elapsedStr);
            }
            default -> {
                return "";
            }
        }
        String style = (p == Phase.THINKING) ? AnsiStyle.CYAN_ITALIC : AnsiStyle.YELLOW;
        return AnsiStyle.wrap(style, content);
    }
}
package org.example.cli.renderer;

import java.time.Duration;

/**
 * ANSI 颜色样式常量与包裹工具。
 *
 * Part 1 设计选择：
 *  - 用裸 ANSI 转义（CSI 序列）而不是 JLine AttributedString。
 *    原因：JLine 的 AttributedString 需要 Terminal 实例才能渲染，而 CliRenderer
 *    当前签名是 void（无 Terminal），简单 ANSI 字符串既能直接 println 到 JLine writer，
 *    也能 fallback 到普通 PrintWriter。
 *  - Windows cmd.exe 不支持 ANSI，但 Windows Terminal / VSCode / mintty 都支持。
 *    兼容性靠 JLine 3.26+ 自动 fallback；如果在 dumb terminal 上颜色失效，最多变成无色文本。
 *
 * part1.md §4.3 样式表（共 6 个语义色）：
 *   ThoughtEvent          → gray dim
 *   ActionPreCheckEvent   → yellow
 *   ActionInvokedEvent    → yellow bold
 *   ObservationEvent      → white
 *   TokenBudgetEvent      → yellow
 *   LoopBudgetEvent       → yellow
 *   FinishEvent           → green bold (FINISH) / red bold (其他 reason)
 *   LoopErrorEvent        → red bold
 *   用户授权请求           → yellow highlight
 */
public final class AnsiStyle {

    private AnsiStyle() {}

    private static final String ESC = "";
    private static final String RESET = ESC + "[0m";
    private static final String BOLD = ESC + "[1m";
    private static final String DIM = ESC + "[2m";
    private static final String ITALIC = ESC + "[3m";

    private static final String FG_GRAY = ESC + "[90m";
    private static final String FG_YELLOW = ESC + "[33m";
    private static final String FG_WHITE = ESC + "[37m";
    private static final String FG_GREEN = ESC + "[32m";
    private static final String FG_RED = ESC + "[31m";
    private static final String FG_CYAN = ESC + "[36m";
    private static final String FG_MAGENTA = ESC + "[35m";

    /** 灰色 + dim —— ThoughtEvent */
    public static final String GRAY_DIM = FG_GRAY + DIM;
    /** 黄色 —— ActionPreCheck / Budget 提示 */
    public static final String YELLOW = FG_YELLOW;
    /** 黄色 + bold —— ActionInvoked */
    public static final String YELLOW_BOLD = FG_YELLOW + BOLD;
    /** 白色 —— ObservationEvent */
    public static final String WHITE = FG_WHITE;
    /** 绿色 + bold —— FinishEvent FINISH */
    public static final String GREEN_BOLD = FG_GREEN + BOLD;
    /** 绿色 —— SubTaskCompleted */
    public static final String GREEN = FG_GREEN;
    /** 红色 —— SubTaskFailed */
    public static final String RED = FG_RED;
    /** 红色 + bold —— FinishEvent 非 FINISH / LoopErrorEvent */
    public static final String RED_BOLD = FG_RED + BOLD;
    /** 黄色 + bold —— 用户授权提示 */
    public static final String YELLOW_BOLD_HIGHLIGHT = FG_YELLOW + BOLD;
    /** 青色 + bold —— prompt dump 段头 */
    public static final String CYAN_BOLD = FG_CYAN + BOLD;
    /** 品红 + bold —— PlanFinished */
    public static final String MAGENTA_BOLD = FG_MAGENTA + BOLD;
    /** 青色 —— VerifyStarted */
    public static final String CYAN = FG_CYAN;
    /** 青色 + italic —— 状态行 Thinking */
    public static final String CYAN_ITALIC = FG_CYAN + ITALIC;

    /**
     * 用 ANSI 样式包裹文本，自动追加 RESET。
     */
    public static String wrap(String style, String text) {
        if (text == null) return "";
        return style + text + RESET;
    }

    /**
     * 把 elapsed 格式化为人类可读字符串。
     * <pre>
     *   0      → "0.0s"
     *   1.5s   → "1.5s"
     *   59.9s  → "59.9s"
     *   60s    → "1m00s"
     *   125s   → "2m05s"
     *   3725s  → "1h02m"
     * </pre>
     */
    public static String elapsedFormat(Duration d) {
        if (d == null) return "0.0s";
        long totalSeconds = d.getSeconds();
        if (totalSeconds < 60L) {
            double seconds = d.toMillis() / 1000.0;
            return String.format("%.1fs", seconds);
        }
        if (totalSeconds < 3600L) {
            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;
            return String.format("%dm%02ds", minutes, seconds);
        }
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        return String.format("%dh%02dm", hours, minutes);
    }

    /**
     * 截断字符串，超过 maxLen 时用 … 结尾。用于工具名等可能被滥用的输入。
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, Math.max(0, maxLen - 1)) + "…";
    }
}
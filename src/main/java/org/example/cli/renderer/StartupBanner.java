package org.example.cli.renderer;

import lombok.extern.slf4j.Slf4j;
import org.example.cli.session.SessionState;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 启动 Banner：ASCII 吉祥物 + 关键信息（启动目录 / 模型 / 会话 / 时间）。
 *
 * <p>模型名来源：
 * <ul>
 *   <li>对话模型：{@code spring.ai.dashscope.chat.options.model}，默认 {@code qwen-plus}</li>
 *   <li>记忆模型：{@code agent.memory.model}，默认 {@code qwen-flash}</li>
 * </ul>
 *
 * <p>Emoji 探测：Windows Terminal / 现代 macOS / Linux 默认支持；
 * 检测到 WT_SESSION / COLORTERM / 非 Windows 平台时启用 emoji，
 * 否则降级为 ASCII 标签（[DIR] / [MODEL] / [MEMORY] / [ID] / [TIME]）。
 */
@Slf4j
@Component
public class StartupBanner {

    private static final String DEFAULT_CHAT_MODEL = "qwen-plus";
    private static final String DEFAULT_MEMORY_MODEL = "qwen-flash";
    private static final String APP_VERSION = "v0.1.0";

    private static final String DIVIDER = "────────────────────────────────────────";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConfigurableEnvironment env;
    private final SessionState session;
    private final Path projectRoot;

    public StartupBanner(ConfigurableEnvironment env,
                          SessionState session) {
        this.env = env;
        this.session = session;
        this.projectRoot = Path.of("").toAbsolutePath();
    }

    public Path projectRoot() {
        return projectRoot;
    }

    /**
     * 打印完整启动 banner 到指定 writer。
     */
    public void print(PrintWriter out) {
        if (out == null) return;
        try {
            String chatModel = readProperty("spring.ai.dashscope.chat.options.model", DEFAULT_CHAT_MODEL);
            String memoryModel = readProperty("agent.memory.model", DEFAULT_MEMORY_MODEL);
            boolean emoji = detectEmojiSupport();
            String sessionId = shortSessionId(session.getSessionId());
            String startTime = formatTime(Instant.now());

            // 吉祥物
            out.println(AnsiStyle.wrap(AnsiStyle.CYAN, "       ∧＿∧"));
            out.println(AnsiStyle.wrap(AnsiStyle.CYAN, "     ( • ᴗ • )"));
            out.println(AnsiStyle.wrap(AnsiStyle.YELLOW_BOLD, "    /  つ⊂   J-Agent"));

            // 分隔
            out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM, DIVIDER));

            // 标题
            out.println(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                    "J-Agent CLI · " + APP_VERSION));

            // 信息行
            String dirLabel = emoji ? "📂 启动目录  " : "[DIR] 启动目录   ";
            String modelLabel = emoji ? "🤖 对话模型  " : "[MODEL] 对话模型 ";
            String memoryLabel = emoji ? "🧠 记忆模型  " : "[MEMORY] 记忆模型";
            String idLabel = emoji ? "🆔 会话 ID   " : "[ID] 会话 ID    ";
            String timeLabel = emoji ? "⏱️ 启动时间  " : "[TIME] 启动时间 ";

            out.println(labelRow(dirLabel, projectRoot.toString()));
            out.println(labelRow(modelLabel, chatModel));
            out.println(labelRow(memoryLabel, memoryModel));
            out.println(labelRow(idLabel, sessionId));
            out.println(labelRow(timeLabel, startTime));

            // 底部提示
            out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM, DIVIDER));
            out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                    "输入 /help 看命令，/model 切模型，Ctrl-D 退出。"));
            out.flush();
        } catch (RuntimeException ex) {
            log.warn("printBanner failed: {}", ex.getMessage());
        }
    }

    private String readProperty(String key, String fallback) {
        try {
            String v = env.getProperty(key);
            return (v == null || v.isBlank()) ? fallback : v;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String labelRow(String label, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.wrap(AnsiStyle.GRAY_DIM, label + ": "));
        sb.append(AnsiStyle.wrap(AnsiStyle.WHITE, value == null ? "" : value));
        return sb.toString();
    }

    private static String shortSessionId(String id) {
        if (id == null || id.isBlank()) return "<unknown>";
        if (id.length() <= 12) return id;
        return id.substring(0, 4) + "-" + id.substring(id.length() - 4);
    }

    private static String formatTime(Instant at) {
        try {
            return TIME_FMT.format(LocalDateTime.ofInstant(at, ZoneId.systemDefault()));
        } catch (RuntimeException ex) {
            return at.toString();
        }
    }

    /**
     * 终端是否支持 emoji 显示。简单启发式：
     *  - Windows Terminal 会设 WT_SESSION 环境变量
     *  - 大部分现代终端会设 COLORTERM=truecolor / 24bit
     *  - macOS Terminal.app、iTerm2、VSCode 内置、Linux gnome-terminal 都通过
     * 否则降级到 ASCII 标签，避免乱码。
     */
    private static boolean detectEmojiSupport() {
        String wt = System.getenv("WT_SESSION");
        if (wt != null && !wt.isBlank()) return true;
        String color = System.getenv("COLORTERM");
        if (color != null && !color.isBlank()) return true;
        String term = System.getenv("TERM");
        if (term != null && term.contains("xterm")) return true;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("linux")) return true;
        // Windows 但不是 WT / COLORTERM —— 假定 cmd.exe / PowerShell，降级
        return false;
    }
}
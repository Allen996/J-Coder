package org.example.cli.repl;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.task.AgentTask;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommandRegistry;
import org.example.cli.input.AtFileResolver;
import org.example.cli.input.InputRouter;
import org.example.cli.input.ShellPassthrough;
import org.example.cli.renderer.AnsiStyle;
import org.example.cli.renderer.CliRenderer;
import org.example.cli.renderer.StartupBanner;
import org.example.cli.renderer.StatusLine;
import org.example.cli.session.SessionState;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * REPL 主循环：JLine 读行 → InputRouter 分发 → Slash / Shell / AgentRuntime。
 *
 * 线程模型：
 *  - REPL 主线程负责 readLine、写输出（terminal.writer()）、调度
 *  - AgentRuntime.stream() 通过 subscribeOn(boundedElastic) 投递到工作线程
 *  - CliRenderer 是 runtime 的全局 observer，在工作线程被触发，事件入 renderQueue
 *  - REPL 主线程在每次循环中 drainTo(out, 100ms) 把渲染好的行 flush 到 terminal.writer()
 *
 * 多行输入：MultiLineReader 状态机判断是否需要续行
 * Ctrl-C  : 忽略（继续）
 * Ctrl-D  : 优雅退出（EndOfFileException 跳出循环）
 */
@Slf4j
@Component
public class ReplLoop {

    private static final String PROMPT_PRIMARY = "[1m[36m▌[0m ";
    private static final String PROMPT_CONTINUATION = "[36m›[0m ";
    private static final long POLL_INTERVAL_MS = 100L;
    /** 状态行刷新间隔：250ms 在大多数终端下足够丝滑，又不闪。 */
    private static final long STATUS_REFRESH_MS = 250L;
    /** part4 §7.8 / §7.2 "会话结束的空闲超时阈值 (默认 10 分钟)"。 */
    private static final long SESSION_IDLE_MINUTES = 10L;

    private final AgentRuntime runtime;
    private final SessionState session;
    private final ConfigurableEnvironment env;
    private final SlashCommandRegistry slashRegistry;
    private final InputRouter inputRouter;
    private final AtFileResolver atFileResolver;
    private final ShellPassthrough shellPassthrough;
    private final CliRenderer renderer;
    private final TaskOrchestrator orchestrator;
    private final StartupBanner banner;
    private final StatusLine statusLine;
    private final MultiLineReader multiLineReader = new MultiLineReader();
    private final SessionMessageStore sessionStore;

    private final Path projectRoot;
    private final PrintWriter out;
    private final Terminal terminal;

    /** 每轮 agent 完成后更新；下次 dispatchAgent 比对差值判定是否空闲超时(part4 §7.8 / §7.2 默认 10 分钟)。 */
    private volatile Instant lastTurnAt = Instant.now();

    public ReplLoop(AgentRuntime runtime,
                    SessionState session,
                    ConfigurableEnvironment env,
                    SlashCommandRegistry slashRegistry,
                    InputRouter inputRouter,
                    AtFileResolver atFileResolver,
                    ShellPassthrough shellPassthrough,
                    CliRenderer renderer,
                    TaskOrchestrator orchestrator,
                    SessionMessageStore sessionStore,
                    StartupBanner banner,
                    StatusLine statusLine) {
        this.runtime = runtime;
        this.session = session;
        this.env = env;
        this.slashRegistry = slashRegistry;
        this.inputRouter = inputRouter;
        this.atFileResolver = atFileResolver;
        this.shellPassthrough = shellPassthrough;
        this.renderer = renderer;
        this.orchestrator = orchestrator;
        this.sessionStore = sessionStore;
        this.banner = banner;
        this.statusLine = statusLine;
        this.projectRoot = Paths.get("").toAbsolutePath();
        try {
            this.terminal = TerminalBuilder.builder().build();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create JLine terminal", ex);
        }
        this.out = terminal.writer();
    }

    public void run() {
        printBanner(); //标题打印
        boolean running = true;
        while (running) {
            String line;
            try {
                line = readMultiline();
            } catch (EndOfFileException eof) {
                running = false;
                break;
            } catch (UserInterruptException ui) {
                // Ctrl-C: redraw prompt
                continue;
            } catch (Exception ex) {
                log.warn("readLine failed", ex);
                running = false;
                break;
            }
            if (line == null) continue;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;

            // Drain any leftover renderer output before processing next input
            renderer.drainTo(out, 0); //处理新输入前清空所有待输出的内容
            
            // 路由，判断输入类型
            try {
                InputRouter.Route route = inputRouter.route(trimmed);
                switch (route) {
                    case SLASH -> slashRegistry.dispatch(trimmed, buildContext());
                    case SHELL -> runShell(trimmed);
                    case AGENT -> dispatchAgent(trimmed, buildContext());
                }
            } catch (EndOfFileException eof) {
                running = false;
            } catch (RuntimeException ex) {
                out.println(AnsiStyle.wrap(AnsiStyle.RED_BOLD, "error: " + ex.getMessage()));
                out.flush();
            }

            // Drain anything left in renderer queue after this turn
            renderer.drainTo(out, 300);
        }

        out.println();
        out.println("bye.");
        out.flush();
        try {
            terminal.close();
        } catch (IOException ex) {
            log.warn("terminal close failed: {}", ex.getMessage());
        }
    }

    // ============== 子流程 ==============

    private String readMultiline() {
        LineReaderBuilder b = LineReaderBuilder.builder().terminal(terminal);
        // 历史记录：按上/下方向键浏览
        b.variable(LineReader.HISTORY_FILE, Paths.get(System.getProperty("user.home"),
                ".local-cli-copilot", "history").toFile());
        LineReader reader = b.build();

        String first = reader.readLine(PROMPT_PRIMARY);
        if (first == null) {
            throw new EndOfFileException();
        }
        MultiLineReader.Accumulation acc = multiLineReader.feed(first);
        while (acc.needsMore()) {
            String next;
            try {
                next = reader.readLine(PROMPT_CONTINUATION);
            } catch (UserInterruptException ui) {
                out.println(AnsiStyle.wrap(AnsiStyle.YELLOW, "^C — abort"));
                out.flush();
                return null;
            } catch (EndOfFileException eof) {
                // 续行被 EOF 中断，强制收尾
                break;
            }
            if (next == null) {
                break;
            }
            acc = multiLineReader.append(acc, next);
        }
        return acc.joined();
    }

    private void runShell(String raw) {
        String cmd = inputRouter.stripShellPrefix(raw);
        ShellPassthrough.ShellResult result = shellPassthrough.run(cmd, projectRoot, line -> {
            out.println(line);
            out.flush();
        });
        if (result.exitCode() != 0) {
            out.println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                    "[exit " + result.exitCode() + "] " + (result.output() == null ? "" : result.output().strip())));
        }
        out.flush();
    }

    private void dispatchAgent(String raw, CliContext ctx) {
        // 空闲超时检测 (part4 §7.8 / §7.2):超过 SESSION_IDLE_MINUTES 分钟视为上一 session 结束,
        // 当前新模型下不再做 mid-term LLM 整体重生成(已删除);改为追加 [meta] session-end 审计事件 + 强制落盘 mid-term。
        Instant now = Instant.now();
        Duration idle = Duration.between(lastTurnAt, now);
        if (idle.toMinutes() >= SESSION_IDLE_MINUTES) {
            try {
                if (sessionStore != null) {
                    sessionStore.addMeta(session.getSessionId(),
                            "[session-end] idle " + idle.toMinutes() + "min >= " + SESSION_IDLE_MINUTES
                                    + "min; mid-term.json persist");
                    sessionStore.persistMidTerm(session.getSessionId());
                }
                out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                        "[memory] idle " + idle.toMinutes() + "min >= " + SESSION_IDLE_MINUTES
                                + "min: mid-term.json persisted for session " + session.getSessionId()));
                out.flush();
            } catch (RuntimeException ex) {
                log.warn("Idle-triggered mid-term persist failed: {}", ex.getMessage());
            }
        }
        //解析带有@的文件，通过resolver进行解析，并报告加载了多少文件，有多少文件没有找到
        AtFileResolver.Resolved resolved = atFileResolver.resolve(raw, projectRoot);
        String finalInput = atFileResolver.buildPrompt(resolved, projectRoot);
        if (finalInput.isBlank()) {
            return;
        }
        if (!resolved.refs().isEmpty()) {
            out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                    "attached " + resolved.refs().size() + " file(s)"));
            out.flush();
        }
        if (resolved.unresolvedCount() > 0) {
            out.println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                    "warning: " + resolved.unresolvedCount() + " unresolved @file ref(s) treated as plain text"));
            out.flush();
        }

        // 创建会话ID、处理过的用户输入、用户角色、promptId
        AgentTask task = AgentTask.builder()
                .sessionId(session.getSessionId())
                .input(finalInput)
                .role("chat")
                .promptId("chat.react-assistant")
                .build();
        
        // 计时锁，等待agent完成
        CountDownLatch done = new CountDownLatch(1);
        runtime.stream(task)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> { /* events routed through CliRenderer observer chain */ },
                        error -> {
                            if (statusLine != null) statusLine.finish();
                            out.println(AnsiStyle.wrap(AnsiStyle.RED_BOLD, "stream error: " + error.getMessage()));
                            out.flush();
                            done.countDown();
                        },
                        done::countDown
                );

        // REPL 主线程轮询 renderer 队列 + 刷新状态行，直到 stream 完成
        while (done.getCount() > 0) {
            renderer.drainTo(out, POLL_INTERVAL_MS);
            redrawStatusLine();
            try {
                if (done.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 强制清掉状态行,确保不会有残留
        if (statusLine != null && statusLine.phase() != StatusLine.Phase.IDLE) {
            statusLine.finish();
        }
        renderer.drainTo(out, 500);

        // Part 5 §8.8 / §8.9: 如果本轮 agent 创建了 TaskPlan，串行驱动 orchestrator
        if (orchestrator.activeGraph().isPresent()) {
            try {
                orchestrator.runActivePlan();
                out.println(AnsiStyle.wrap(AnsiStyle.CYAN_BOLD,
                        "[orchestrator] plan "
                                + orchestrator.activeGraph().map(p -> p.getPlanId()).orElse("?")
                                + " processed"));
                out.flush();
            } catch (RuntimeException ex) {
                out.println(AnsiStyle.wrap(AnsiStyle.RED_BOLD,
                        "[orchestrator] failed: " + ex.getMessage()));
                out.flush();
            }
        }

        // 本轮 agent 收尾完成,刷新 lastTurnAt 给下次 idle 检测比对。
        lastTurnAt = Instant.now();
    }

    private CliContext buildContext() {
        return new CliContext(runtime, session, out, projectRoot, env);
    }

    private void printBanner() {
        banner.print(out);
        printResumablePlans();
        out.flush();
    }

    /**
     * 启动时列出磁盘上 ACTIVE 状态的 plan（part5 §8.9 "进程崩溃重启:扫描 plan.json"）。
     *
     * <p>非阻塞 —— 只打印一行提示，由用户决定是否输入 {@code /resume <planId>}。
     * 不在 @PostConstruct 做是因为 stdin 尚未被 JLine 接管，不应阻塞上下文初始化。
     */
    private void printResumablePlans() {
        // 阶段 2 起:DagStateRepository 按 sessionId 组织,通过 sessionStore.sessionsRoot 扫描
        if (sessionStore == null) return;
        try {
            Path sessionsRoot = sessionStore.sessionsRoot();
            if (!Files.exists(sessionsRoot)) return;
            int count = 0;
            StringBuilder lines = new StringBuilder();
            try (var stream = Files.list(sessionsRoot)) {
                for (Path dir : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(dir)) continue;
                    Path planJson = dir.resolve("plan.json");
                    if (!Files.exists(planJson)) continue;
                    lines.append("\n  · session=").append(dir.getFileName());
                    count++;
                }
            }
            if (count == 0) return;
            out.println(AnsiStyle.wrap(AnsiStyle.YELLOW_BOLD,
                    "[resume] " + count + " session(s) with plan.json found on disk:"));
            out.print(lines);
            out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                    "  use /tasks to inspect active plan"));
        } catch (Exception ex) {
            log.warn("printResumablePlans failed: {}", ex.getMessage());
        }
    }

    /**
     * 重绘状态行：每 STATUS_REFRESH_MS 调一次。
     * 用 CR + EL 把当前行清掉重画，避免堆积。
     */
    private void redrawStatusLine() {
        if (statusLine == null) return;
        if (statusLine.phase() == StatusLine.Phase.IDLE) return;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastStatusDrawMs < STATUS_REFRESH_MS) return;
        lastStatusDrawMs = nowMs;
        String line = statusLine.renderLine(java.time.Instant.ofEpochMilli(nowMs));
        if (line == null || line.isEmpty()) return;
        // CR + ESC[K 清掉当前行内容,然后写新行
        out.print("\u001b[K");
        out.println(line);
        out.flush();
    }
    private volatile long lastStatusDrawMs = 0L;

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
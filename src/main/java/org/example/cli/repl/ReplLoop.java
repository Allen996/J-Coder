package org.example.cli.repl;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.memory.MemoryTurnHook;
import org.example.agent.context.memory.MidTermStore;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.task.AgentTask;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommandRegistry;
import org.example.cli.input.AtFileResolver;
import org.example.cli.input.InputRouter;
import org.example.cli.input.ShellPassthrough;
import org.example.cli.renderer.AnsiStyle;
import org.example.cli.renderer.CliRenderer;
import org.example.cli.renderer.TaskProgressRenderer;
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
    private final TaskProgressRenderer taskRenderer;
    private final TaskOrchestrator orchestrator;
    private final TaskPlanRepository taskPlanRepository;
    private final MultiLineReader multiLineReader = new MultiLineReader();
    private final MemoryTurnHook memoryTurnHook;

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
                    TaskProgressRenderer taskRenderer,
                    TaskOrchestrator orchestrator,
                    TaskPlanRepository taskPlanRepository,
                    MemoryTurnHook memoryTurnHook) {
        this.runtime = runtime;
        this.session = session;
        this.env = env;
        this.slashRegistry = slashRegistry;
        this.inputRouter = inputRouter;
        this.atFileResolver = atFileResolver;
        this.shellPassthrough = shellPassthrough;
        this.renderer = renderer;
        this.taskRenderer = taskRenderer;
        this.orchestrator = orchestrator;
        this.taskPlanRepository = taskPlanRepository;
        this.memoryTurnHook = memoryTurnHook;
        this.projectRoot = Paths.get("").toAbsolutePath();
        try {
            this.terminal = TerminalBuilder.builder().build();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create JLine terminal", ex);
        }
        this.out = terminal.writer();
    }

    public void run() {
        printBanner();
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
            renderer.drainTo(out, 0);

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
        // 触发 mid-term 整体重生成(part4 §7.2 "会话结束时整体重生成")。
        Instant now = Instant.now();
        if (memoryTurnHook != null) {
            Duration idle = Duration.between(lastTurnAt, now);
            if (idle.toMinutes() >= SESSION_IDLE_MINUTES) {
                try {
                    MidTermStore.MidTerm regenerated = memoryTurnHook.regenerateForSession(session.getSessionId());
                    out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                            "[memory] idle " + idle.toMinutes() + "min >= " + SESSION_IDLE_MINUTES
                                    + "min: regenerated mid-term for session "
                                    + session.getSessionId()
                                    + (regenerated == null ? " (no-op)" : "")));
                    out.flush();
                } catch (RuntimeException ex) {
                    log.warn("Idle-triggered mid-term regeneration failed: {}", ex.getMessage());
                }
            }
        }

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

        AgentTask task = AgentTask.builder()
                .sessionId(session.getSessionId())
                .input(finalInput)
                .role("chat")
                .promptId("chat.react-assistant")
                .build();

        CountDownLatch done = new CountDownLatch(1);
        runtime.stream(task)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> { /* events routed through CliRenderer observer chain */ },
                        error -> {
                            out.println(AnsiStyle.wrap(AnsiStyle.RED_BOLD, "stream error: " + error.getMessage()));
                            out.flush();
                            done.countDown();
                        },
                        done::countDown
                );

        // REPL 主线程轮询 renderer 队列，直到 stream 完成
        while (done.getCount() > 0) {
            renderer.drainTo(out, POLL_INTERVAL_MS);
            try {
                if (done.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        renderer.drainTo(out, 500);

        // Part 5 §8.8 / §8.9: 如果本轮 agent 创建了 TaskPlan，串行驱动 orchestrator
        if (orchestrator.activePlan().isPresent()
                && orchestrator.activePlan().get().getStatus()
                        == org.example.agent.core.task.TaskPlanStatus.ACTIVE) {
            try {
                orchestrator.runActivePlan();
                taskRenderer.drainTo(out, 200);
                out.println(AnsiStyle.wrap(AnsiStyle.CYAN_BOLD,
                        "[orchestrator] plan "
                                + orchestrator.activePlan().map(p -> p.getPlanId()).orElse("?")
                                + " finished: "
                                + orchestrator.activePlan().map(p -> p.getStatus().name()).orElse("?")));
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
        out.println(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                "SuperBizAgent CLI · model=" + session.getCurrentModel() +
                        " · session=" + session.getSessionId() +
                        " · type /help"));
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
        if (taskPlanRepository == null) return;
        try {
            List<TaskPlan> active = taskPlanRepository.listAllPlans().stream()
                    .filter(p -> p.getStatus() == TaskPlanStatus.ACTIVE)
                    .toList();
            if (active.isEmpty()) return;
            out.println(AnsiStyle.wrap(AnsiStyle.YELLOW_BOLD,
                    "[resume] " + active.size() + " active plan(s) found on disk:"));
            for (TaskPlan p : active) {
                String paused = p.isPaused() ? " (paused)" : "";
                String current = p.getCurrentTaskId() == null ? "" : "  current=" + p.getCurrentTaskId();
                out.println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                        "  · " + p.getPlanId() + "  goal=" + truncate(p.getGoal(), 60) + paused + current));
            }
            out.println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                    "  use /resume <planId> to adopt & resume, or /tasks to inspect"));
        } catch (RuntimeException ex) {
            log.warn("printResumablePlans failed: {}", ex.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
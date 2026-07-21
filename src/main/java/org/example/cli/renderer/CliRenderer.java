package org.example.cli.renderer;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.event.AgentEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.signal.ReActLoopSignal;
import org.example.cli.session.SessionState;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把 {@link AgentEvent} 流渲染为 ANSI 文本行，按 part1.md §4.3 表分派。
 *
 * 线程模型：
 *  - 实现 {@link ReActLoopObserver}，被 AgentRuntime 同步调用（订阅线程）
 *  - 不直接写 PrintWriter —— 而是 push 到 {@link #renderQueue}
 *  - REPL 主线程在两次 readLine 之间调 {@link #drainTo(PrintWriter)} 拉取渲染好的行
 *  - 这样避免 observer 线程和 REPL 线程并发写 JLine writer 的问题
 *
 * 副作用：
 *  - onThought 累加 token 计数到 SessionState（/cost 数据源）
 *  - onFinish 把 finalAnswer append 到 SessionState.historyForExport（/export 数据源）
 */
@Slf4j
@Component
public class CliRenderer implements ReActLoopObserver {

    private static final int QUEUE_CAPACITY = 1024;
    private static final int OBSERVATION_FOLD_THRESHOLD = 100;

    private final SessionState session;
    private final AgentRuntime runtime;

    private final BlockingQueue<String> renderQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    public CliRenderer(SessionState session, @Lazy AgentRuntime runtime) {
        this.session = session;
        this.runtime = runtime;
    }

    @PostConstruct
    public void register() {
        runtime.registerObserver(this);
        log.debug("CliRenderer registered as global ReActLoopObserver");
    }

    /**
     * 由 REPL 主线程调用：把所有已渲染的行 flush 到 out，超时则返回。
     * 不阻塞超过 timeoutMs。
     */
    public int drainTo(PrintWriter out, long timeoutMs) {
        if (out == null) return 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        int n = 0;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            String line;
            try {
                line = renderQueue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            if (line == null) break;
            out.println(line);
            n++;
        }
        out.flush();
        return n;
    }

    // ============== Observer 实现 ==============

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        session.addTokens(event.getPromptTokens(), event.getCompletionTokens());
        String text = event.getThoughtText();
        if (text == null) return;
        // 非流式：ThoughtEvent 携带的就是模型完整回答 —— 逐行白文输出，不截断、不加前缀。
        // verbose 模式在首行追加 "(prompt=N, completion=M)"。
        boolean showTokens = session.isVerbose()
                && (event.getPromptTokens() > 0 || event.getCompletionTokens() > 0);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.wrap(AnsiStyle.WHITE, lines[i]));
            if (i == 0 && showTokens) {
                sb.append(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                        String.format(" (prompt=%d, completion=%d)",
                                event.getPromptTokens(), event.getCompletionTokens())));
            }
            enqueue(sb.toString());
        }
    }

    @Override
    public void onActionPreCheck(org.example.agent.core.event.ActionPreCheckEvent event, ReActLoopSignal signal) {
        String argsStr = formatArgs(event.getArgs());
        enqueue(AnsiStyle.wrap(AnsiStyle.YELLOW,
                String.format("⚙ %s(%s)", event.getToolName(), argsStr)));
    }

    @Override
    public void onActionInvoked(org.example.agent.core.event.ActionInvokedEvent event, ReActLoopSignal signal) {
        // status 字段当前为空（ActionInvokedEvent 只携带 toolName），渲染时省去 status 显示
        enqueue(AnsiStyle.wrap(AnsiStyle.YELLOW_BOLD,
                String.format("⚙ %s → invoked", event.getToolName())));
    }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        String text = event.getObservationText();
        if (text == null) text = "";
        int lineCount = text.isEmpty() ? 0 : text.split("\n", -1).length;
        if (text.length() > OBSERVATION_FOLD_THRESHOLD) {
            enqueue(AnsiStyle.wrap(AnsiStyle.WHITE,
                    String.format("⎡ 输出 ⎦  %d lines", lineCount)));
        } else {
            // ObservationEvent 多行内容逐行输出，每行单独白色
            if (text.isEmpty()) {
                enqueue(AnsiStyle.wrap(AnsiStyle.WHITE, "⎡ 输出 ⎦  (empty)"));
            } else {
                for (String line : text.split("\n", -1)) {
                    enqueue(AnsiStyle.wrap(AnsiStyle.WHITE, line));
                }
            }
        }
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) {
        enqueue(AnsiStyle.wrap(AnsiStyle.YELLOW,
                String.format("⚠ step %d/%d", event.getStepsTaken(), event.getMaxSteps())));
    }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) {
        long max = event.getMaxTokens() <= 0 ? 1 : event.getMaxTokens();
        long pct = (event.getTokensUsed() * 100L) / max;
        enqueue(AnsiStyle.wrap(AnsiStyle.YELLOW,
                String.format("⚠ token %d%% used (%d/%d)", pct, event.getTokensUsed(), max)));
    }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) {
        String answer = event.getFinalAnswer();
        // 注意：模型的完整回答已在 onThought 里全量打印，
        // 这里不再重复打印 finalAnswer，避免双重输出。
        if (event.getReason() == FinishReason.FINISH) {
            enqueue(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                    String.format("✓ 完成 (%d tokens)", event.getTotalTokensUsed() == null ? 0 : event.getTotalTokensUsed())));
            session.appendHistory(answer == null ? "" : answer);
        } else {
            enqueue(AnsiStyle.wrap(AnsiStyle.RED_BOLD,
                    String.format("✗ %s", event.getReason())));
        }
    }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) {
        enqueue(AnsiStyle.wrap(AnsiStyle.RED_BOLD,
                String.format("✗ %s", event.getMessage() == null ? "error" : event.getMessage())));
    }

    // ============== helpers ==============

    private void enqueue(String line) {
        try {
            if (!renderQueue.offer(line, 100, TimeUnit.MILLISECONDS)) {
                log.warn("renderQueue full; dropping line: {}", abbreviate(line, 80));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String formatArgs(java.util.Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (java.util.Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append('=');
            Object v = e.getValue();
            sb.append(v == null ? "null" : abbreviate(String.valueOf(v), 40));
        }
        return sb.toString();
    }
}
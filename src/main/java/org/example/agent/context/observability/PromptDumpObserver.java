package org.example.agent.context.observability;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.signal.ReActLoopSignal;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可观测的 prompt dump（part3.md 可观测增强 / part5.md §11 调试模式）。
 *
 * <p>职责：
 * <ul>
 *   <li>每次 ReAct loop 发往 LLM 的消息列表（via {@link #onPromptBuilt}）快照到本 observer。</li>
 *   <li>支持 verbose 模式：把 prompt 写到 {@link PrintWriter}（即 REPL 的 {@code ctx.out()}）。
 *      该方法不引用 cli 模块的 ANSI 库，由调用方自行着色；本类输出纯文本 + 可选 ANSI 标记。</li>
 *   <li>支持按 executionId 查询当前最新 prompt —— 给 /context 命令用。</li>
 *   <li>支持关闭 verbose（仅保留快照，不打印）。</li>
 * </ul>
 *
 * <p>线程模型：
 * <ul>
 *   <li>{@link #onPromptBuilt} 在 ReAct 子线程回调 —— 写入 snapshot 是同步的。</li>
 *   <li>{@link #dump(PrintWriter)} 在 REPL 主线程调用 —— 读 snapshot 也是同步的。</li>
 *   <li>由于 List 是同步构造后整体发布，并发安全不依赖额外锁。</li>
 * </ul>
 */
@Slf4j
@Component
public class PromptDumpObserver implements ReActLoopObserver {

    private final AgentRuntime runtime;
    private volatile boolean verbose = false;
    private volatile PrintWriter verboseWriter = null;

    public PromptDumpObserver(@Lazy AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @PostConstruct
    public void register() {
        if (!runtime.registeredObservers().contains(this)) {
            runtime.registerObserver(this);
            log.debug("PromptDumpObserver registered as global ReActLoopObserver");
        }
    }

    /** executionRef -> 最新 prompt snapshot。 */
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private volatile Snapshot latestSnapshot;

    @Getter
    public static final class Snapshot {
        private final String executionRef;
        private final int stepIndex;
        private final List<Message> messages;
        private final long capturedAt;

        public Snapshot(String executionRef, int stepIndex, List<Message> messages) {
            this.executionRef = executionRef;
            this.stepIndex = stepIndex;
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
            this.capturedAt = System.currentTimeMillis();
        }

        public int size() {
            return messages.size();
        }

        public long estimateTokens() {
            long t = 0;
            for (Message m : messages) {
                t += ContextBudgetPolicy.estimateTextTokens(extractText(m));
            }
            return t;
        }
    }

    // ============ 控制接口 ============

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
        if (verbose) {
            log.info("PromptDumpObserver: verbose mode enabled");
        }
    }

    public boolean isVerbose() {
        return verbose;
    }

    /** 把 verbose 输出导向一个 writer（通常是 REPL 的 out）。 */
    public void setVerboseWriter(PrintWriter writer) {
        this.verboseWriter = writer;
    }

    /** /context 命令 / 测试用：返回最近被设置的一个 snapshot。 */
    public Snapshot latestSnapshot() {
        return latestSnapshot;
    }

    public void clearSnapshots() {
        snapshots.clear();
        latestSnapshot = null;
    }

    // ============ 渲染工具 ============

    /**
     * dump 一个 snapshot 到指定 writer。verbose 模式自动调；/context 命令也调它。
     *
     * @param maxMessageChars 单条消息渲染的最大字符数；≤0 表示不截断
     */
    public void dump(PrintWriter writer, int maxMessageChars) {
        if (writer == null) return;
        Snapshot snap = latestSnapshot();
        if (snap == null) {
            writer.println("(no prompt captured yet)");
            writer.flush();
            return;
        }
        dumpSnapshot(snap, writer, maxMessageChars, true);
        writer.flush();
    }

    public void dumpSnapshot(Snapshot snap, PrintWriter writer, int maxMessageChars, boolean showBanner) {
        if (snap == null || writer == null) return;
        if (showBanner) {
            writer.println();
            writer.println(String.format(
                    "─── prompt dump ─── execRef=%s step=%d msgs=%d estTokens=%d",
                    snap.executionRef, snap.stepIndex, snap.messages.size(), snap.estimateTokens()));
            writer.println(String.format(
                    "    total est. ≈ %d tokens (≈ %d chars / 4)",
                    snap.estimateTokens(), snap.estimateTokens() * 4));
        }
        int idx = 0;
        for (Message m : snap.messages) {
            idx++;
            String role = roleOf(m);
            String text = extractText(m);
            String truncated = text;
            if (maxMessageChars > 0 && text.length() > maxMessageChars) {
                truncated = text.substring(0, maxMessageChars)
                        + String.format("… [+%d chars]", text.length() - maxMessageChars);
            }
            writer.println();
            writer.println(String.format("── msg[%d/%d] role=%s ────────────────────────",
                    idx, snap.messages.size(), role));
            for (String line : truncated.split("\n", -1)) {
                writer.println("    " + line);
            }
        }
        if (showBanner) {
            writer.println("─── /prompt dump ───");
            writer.println();
        }
    }

    private static String roleOf(Message m) {
        if (m instanceof SystemMessage) return "system";
        if (m instanceof UserMessage) return "user";
        if (m instanceof AssistantMessage) return "assistant";
        if (m instanceof ToolResponseMessage) return "tool";
        return m.getClass().getSimpleName();
    }

    static String extractText(Message m) {
        if (m == null) return "";
        if (m instanceof SystemMessage sys) return sys.getText();
        if (m instanceof UserMessage user) return user.getText();
        if (m instanceof AssistantMessage asst) {
            StringBuilder sb = new StringBuilder();
            String text = asst.getText();
            if (text != null) sb.append(text);
            if (asst.hasToolCalls()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("(tool_calls: ");
                for (int i = 0; i < asst.getToolCalls().size(); i++) {
                    if (i > 0) sb.append(", ");
                    AssistantMessage.ToolCall tc = asst.getToolCalls().get(i);
                    sb.append(tc.name())
                            .append('(')
                            .append(tc.arguments() == null ? "" : tc.arguments())
                            .append(')');
                }
                sb.append(')');
            }
            return sb.toString();
        }
        if (m instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < trm.getResponses().size(); i++) {
                if (i > 0) sb.append("\n");
                ToolResponseMessage.ToolResponse r = trm.getResponses().get(i);
                sb.append('[').append(r.name()).append("] ").append(r.responseData());
            }
            return sb.toString();
        }
        return m.toString();
    }

    // ============ Observer 回调 ============

    @Override
    public void onPromptBuilt(List<Message> messages, int stepIndex) {
        if (messages == null) return;
        // ReActLoop 的 messages 列表是该 execution 内部复用的 ArrayList 实例；
        // 用 identityHashCode 作为同一个 execution 的引用 key。
        String execRef = "exec-" + System.identityHashCode(messages);
        Snapshot snapshot = new Snapshot(execRef, stepIndex, messages);
        snapshots.put(execRef, snapshot);
        latestSnapshot = snapshot;

        if (verbose && verboseWriter != null) {
            try {
                dumpSnapshot(snapshot, verboseWriter, 800, true);
            } catch (Exception ex) {
                log.warn("PromptDumpObserver: verbose dump failed: {}", ex.getMessage());
            }
        }
    }

    @Override public void onThought(ThoughtEvent event, ReActLoopSignal signal) { }
    @Override public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) { }
    @Override public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) { }
    @Override public void onObservation(ObservationEvent event, ReActLoopSignal signal) { }
    @Override public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) { }
    @Override public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) { }
    @Override public void onFinish(FinishEvent event, ReActLoopSignal signal) { }
    @Override public void onError(LoopErrorEvent event, ReActLoopSignal signal) { }
}
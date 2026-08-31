package org.example.agent.core.task.subagent;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.session.SessionCompressor;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.handle.AgentHandle;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.result.AgentExecutionResult;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.task.AgentTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SubAgentRunner 的默认实现（阶段 3 接入 SessionCompressor）。
 *
 * <p>用独立 {@code AgentTask.agentRole="subagent"} + executionId 启动 AgentRuntime，
 * 在专属线程池中跑并由 {@code timeoutMs} 控制最大时长。
 *
 * <p>超时处理：
 * <ul>
 *   <li>调用 {@link AgentHandle#cancelNow()}（注册中心里有 handle 时）触发协作式中断</li>
 *   <li>5 秒后若 future 仍未结束,强制返回 TIMEOUT；后台线程继续跑但不再 await</li>
 * </ul>
 *
 * <p>阶段 3 接入（K.2 + R）：
 * <ul>
 *   <li>SubAgent 跑完后（包括 COMPLETED / FAILED / TIMEOUT 所有路径）调
 *       {@link SessionCompressor#compressIfPresent} 把 short-term.json 折叠进 mid-term.json</li>
 *   <li>short-term 不存在时幂等 noop（R）</li>
 * </ul>
 */
@Slf4j
@Component
public class LocalSubAgentRunner implements SubAgentRunner {

    private final AgentRuntime agentRuntime;
    private final ObjectProvider<ContextBuilder> contextBuilderProvider;
    private final SessionCompressor compressor;
    private final ObjectProvider<SessionMessageStore> sessionStoreProvider;
    /** 专用线程池：每个 SubAgent 跑一条线程，避免与主 Agent loop 线程冲突。 */
    private final ExecutorService pool;
    private final Path sessionsRoot;

    @Autowired
    public LocalSubAgentRunner(AgentRuntime agentRuntime,
                               ObjectProvider<ContextBuilder> contextBuilderProvider,
                               SessionCompressor compressor,
                               ObjectProvider<SessionMessageStore> sessionStoreProvider) {
        this.agentRuntime = agentRuntime;
        this.contextBuilderProvider = contextBuilderProvider;
        this.compressor = compressor;
        this.sessionStoreProvider = sessionStoreProvider;
        this.pool = Executors.newCachedThreadPool(new SubAgentThreadFactory());
        this.sessionsRoot = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve(".agent")
                .resolve("sessions");
    }

    @Override
    public SubAgentResult run(SubAgentTask task) {
        long started = System.currentTimeMillis();
        String executionId = "subagent-" + task.taskId() + "-" + UUID.randomUUID().toString().substring(0, 8);

        AgentTask agentTask = AgentTask.builder()
                .sessionId(task.taskId())
                .input(buildSubAgentInput(task))
                .role("subagent")
                .agentRole("subagent")
                .promptId("task.subagent-executor")
                .promptVariables(buildPromptVariables(task))
                .budget(resolveBudget(task))
                .includeHistory(false)
                .build();

        String sessionPath = sessionsRoot.resolve(task.taskId()).toString();

        Callable<SubAgentResult> callable = () -> doRun(executionId, agentTask, task);
        FutureTask<SubAgentResult> ft = new FutureTask<>(callable);
        pool.execute(ft);
        try {
            SubAgentResult result = ft.get(task.timeoutMs(), TimeUnit.MILLISECONDS);
            compressAfterRun(task);
            return result;
        } catch (TimeoutException ex) {
            log.warn("SubAgent {} timed out after {}ms; cancel and return TIMEOUT",
                    task.taskId(), task.timeoutMs());
            cancelExecution(executionId);
            try {
                SubAgentResult result = ft.get(5_000L, TimeUnit.MILLISECONDS);
                compressAfterRun(task);
                return result;
            } catch (Exception ignored) {
                ft.cancel(true);
                long durationMs = System.currentTimeMillis() - started;
                SubAgentResult timeoutResult = new SubAgentResult(
                        task.taskId(),
                        SubAgentStatus.TIMEOUT,
                        "",
                        "SubAgent exceeded timeout " + task.timeoutMs() + "ms",
                        List.of(),
                        List.of(),
                        durationMs,
                        sessionPath);
                compressAfterRun(task);
                return timeoutResult;
            }
        } catch (ExecutionException ex) {
            long durationMs = System.currentTimeMillis() - started;
            log.error("SubAgent {} execution threw: {}", task.taskId(), ex.getCause().getMessage(), ex.getCause());
            SubAgentResult failedResult = new SubAgentResult(
                    task.taskId(),
                    SubAgentStatus.FAILED,
                    "",
                    "SubAgent threw: " + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()),
                    List.of(),
                    List.of(),
                    durationMs,
                    sessionPath);
            compressAfterRun(task);
            return failedResult;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            ft.cancel(true);
            long durationMs = System.currentTimeMillis() - started;
            SubAgentResult interruptedResult = new SubAgentResult(
                    task.taskId(),
                    SubAgentStatus.FAILED,
                    "",
                    "interrupted",
                    List.of(),
                    List.of(),
                    durationMs,
                    sessionPath);
            compressAfterRun(task);
            return interruptedResult;
        }
    }

    /**
     * 阶段 3（R 决定）：SubAgent 跑完后调压缩,把 short-term 折叠进 mid-term。
     * best-effort,失败仅 warn —— 不影响主流程。
     */
    private void compressAfterRun(SubAgentTask task) {
        try {
            Path sessionDir = sessionsRoot.resolve(task.taskId());
            SessionMessageStore store = sessionStoreProvider.getIfAvailable();
            if (store == null) {
                log.debug("compressAfterRun: SessionMessageStore not available; skip");
                return;
            }
            compressor.compressIfPresent(sessionDir, store.mapper());
        } catch (Exception ex) {
            log.warn("compressAfterRun failed for {}: {}", task.taskId(), ex.getMessage());
        }
    }

    private SubAgentResult doRun(String executionId, AgentTask agentTask, SubAgentTask task) {
        AgentExecutionResult result;
        try {
            result = agentRuntime.execute(agentTask);
        } catch (RuntimeException ex) {
            log.warn("SubAgent {} runtime exception: {}", task.taskId(), ex.getMessage());
            return new SubAgentResult(
                    task.taskId(),
                    SubAgentStatus.FAILED,
                    "",
                    "runtime exception: " + ex.getMessage(),
                    List.of(),
                    List.of(),
                    0L,
                    sessionsRoot.resolve(task.taskId()).toString());
        }

        FinishReason reason = result == null ? FinishReason.ERROR : result.getReason();
        String finalAnswer = result == null ? "" : (result.getFinalAnswer() == null ? "" : result.getFinalAnswer());
        long durationMs = 0L;

        if (reason == FinishReason.CANCELLED) {
            return new SubAgentResult(
                    task.taskId(),
                    SubAgentStatus.TIMEOUT,
                    finalAnswer,
                    "SubAgent cancelled (likely timeout)",
                    List.of(),
                    List.of(),
                    durationMs,
                    sessionsRoot.resolve(task.taskId()).toString());
        }
        if (reason == FinishReason.ERROR) {
            return new SubAgentResult(
                    task.taskId(),
                    SubAgentStatus.FAILED,
                    finalAnswer,
                    "SubAgent finished with ERROR",
                    List.of(),
                    List.of(),
                    durationMs,
                    sessionsRoot.resolve(task.taskId()).toString());
        }
        return new SubAgentResult(
                task.taskId(),
                SubAgentStatus.COMPLETED,
                finalAnswer,
                "",
                List.of(),
                List.of(),
                durationMs,
                sessionsRoot.resolve(task.taskId()).toString());
    }

    private void cancelExecution(String executionId) {
        try {
            AgentHandle handle = agentRuntime.cancel(executionId);
            if (handle != null) {
                log.debug("SubAgent {} cancel requested via ExecutionRegistry", executionId);
            }
        } catch (RuntimeException ex) {
            log.warn("SubAgent cancel failed: {}", ex.getMessage());
        }
    }

    private AgentBudget resolveBudget(SubAgentTask task) {
        return AgentBudget.builder()
                .maxSteps(24)
                .maxTotalTokens(80_000)
                .maxWallClock(java.time.Duration.ofMillis(task.timeoutMs()))
                .build();
    }

    private static String buildSubAgentInput(SubAgentTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("[SubTask ").append(task.taskId()).append("] ").append(task.title()).append("\n\n");
        sb.append(task.description());
        if (!task.expectedOutput().isBlank()) {
            sb.append("\n\n-- expected output --\n").append(task.expectedOutput()).append("\n");
        }
        if (!task.contextFiles().isEmpty()) {
            sb.append("\n-- files to read first --\n");
            for (String f : task.contextFiles()) sb.append("- ").append(f).append("\n");
        }
        sb.append("\n完成本任务后用 final answer 报告结果；不要派发新的子任务。\n");
        return sb.toString();
    }

    private static java.util.Map<String, Object> buildPromptVariables(SubAgentTask task) {
        java.util.Map<String, Object> vars = new java.util.HashMap<>();
        vars.put("taskId", task.taskId());
        vars.put("title", task.title());
        vars.put("description", task.description());
        vars.put("expectedOutput", task.expectedOutput());
        vars.put("contextFiles", task.contextFiles());
        vars.put("parentSessionId", task.parentSessionId());
        vars.put("parentCheckpointId", task.parentCheckpointId());
        return vars;
    }

    /** SubAgent 线程命名 —— 便于 jstack 排查。 */
    private static final class SubAgentThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "subagent-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
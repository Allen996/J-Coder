package org.example.agent.tool.gateway;

import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.RollbackEvent;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.signal.ReActLoopSignal;
import org.example.agent.tool.ToolDeniedException;
import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.failure.FailureClassifier;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.RetryPolicy;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.result.ToolError;
import org.example.agent.tool.result.ToolResult;
import org.example.agent.tool.rollback.RollbackSummary;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.agent.tool.sandbox.AuthorizationGate;
import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.example.agent.tool.spi.ToolRisk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具调用的中央入口。
 *
 * <p>ReActLoop 拿到 LLM 的 tool_calls 后，循环调用本类的 {@link #invoke}。
 * 本类负责：
 * <ol>
 *   <li>查 {@link ToolDescriptorRegistry} 拿元数据（risk / reversible / timeoutMs / cacheable / readonly）</li>
 *   <li>发 {@link ActionPreCheckEvent}</li>
 *   <li>查 Spring AI {@link ToolCallback} by name</li>
 *   <li>发 {@link ActionInvokedEvent}</li>
 *   <li>用 {@link RetryPolicy} 包裹真实调用,内部通过 {@link CompletableFuture} + {@code toolExecutor} 施加超时</li>
 *   <li>成功后将结果(若 cacheable)写入 {@link ToolResultStore},并在末尾追加 {@code [stored as #<id>]}</li>
 *   <li>异常时用 {@link FailureClassifier} 分类,包装成结构化错误返回 LLM;若 LOGIC + reversible 则回滚</li>
 *   <li>发 {@link ObservationEvent}</li>
 * </ol>
 *
 * <p>沙箱校验（路径闸 / 命令闸）由 {@code @Tool} 方法自己调用，本类不做。
 */
@Component
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    private final Map<String, ToolCallback> callbacks;
    private final ToolDescriptorRegistry descriptorRegistry;
    private final FailureClassifier classifier;
    private final RetryPolicy retryPolicy;
    private final SideEffectTracker sideEffects;
    private final CliToolProperties properties;
    private final ExecutorService toolExecutor;
    private final AuthorizationGate authGate;

    @Autowired
    public ToolGateway(ToolCallbackProvider toolCallbackProvider,
                       ToolDescriptorRegistry descriptorRegistry,
                       FailureClassifier classifier,
                       RetryPolicy retryPolicy,
                       SideEffectTracker sideEffects,
                       CliToolProperties properties,
                       @Qualifier("toolExecutor") ExecutorService toolExecutor,
                       AuthorizationGate authGate) {
        this.callbacks = java.util.Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        c -> c,
                        (a, b) -> {
                            log.warn("duplicate ToolCallback name: {}", a.getToolDefinition().name());
                            return a;
                        }));
        this.descriptorRegistry = descriptorRegistry;
        this.classifier = classifier;
        this.retryPolicy = retryPolicy;
        this.sideEffects = sideEffects;
        this.properties = properties;
        this.toolExecutor = toolExecutor;
        this.authGate = authGate;
    }

    /**
     * 向后兼容的测试用 5 参构造器。生产路径不会走到 —— Spring 注入 8 参版。
     * 旧测试默认走"全放行"授权闸，保证 MEDIUM 工具不被新闸误拦。
     */
    public ToolGateway(ToolCallbackProvider toolCallbackProvider,
                       ToolDescriptorRegistry descriptorRegistry,
                       FailureClassifier classifier,
                       RetryPolicy retryPolicy,
                       SideEffectTracker sideEffects) {
        this(toolCallbackProvider, descriptorRegistry, classifier, retryPolicy, sideEffects,
                new CliToolProperties(), null, AllowAllAuthorizationGate.INSTANCE);
    }

    public Optional<ToolCallback> lookup(String name) {
        return Optional.ofNullable(callbacks.get(name));
    }

    /**
     * 执行一次工具调用。返回 LLM 看到的响应（成功时是工具输出，失败时是结构化错误摘要）。
     *
     * <p>不会向上抛异常 —— 所有失败都被翻译成结构化错误字符串。
     */
    public String invoke(String executionId,
                         String toolName,
                         String argsJson,
                         int stepIndex,
                         ReActLoopSignal signal,
                         List<ReActLoopObserver> observers) {
        log.info("ToolGateway.invoke: executionId={} toolName='{}' argsJson={} knownCallbacks={}",
                executionId, toolName,
                argsJson == null ? "<null>" : (argsJson.length() > 200 ? argsJson.substring(0, 200) + "..." : argsJson),
                callbacks.keySet());
        Instant preCheckAt = Instant.now();
        Map<String, Object> argsMap = parseArgs(argsJson);
        emitPreCheck(executionId, preCheckAt, stepIndex, toolName, argsMap, signal, observers);

        ToolCallback cb = callbacks.get(toolName);
        if (cb == null) {
            String err = formatUnknownTool(toolName, argsMap);
            emitObservation(executionId, stepIndex, toolName, ObservationEvent.Status.ERROR, err, 0L, signal, observers);
            return err;
        }

        ToolDescriptor descriptor = descriptorRegistry.get(toolName).orElse(null);
        long timeoutMs = resolveTimeoutMs(descriptor);
        long start = System.currentTimeMillis();

        // ===== Authorization gate (MEDIUM / HIGH) =====
        // 在沙箱闸（CommandGate / PathGate，工具自身调用）之后再次校验：命令闸已拦过
        // 黑名单模式，本闸只对未被工具层拒绝的中高风险调用起作用。
        // 拒绝时抛 ToolDeniedException，由下方统一进 handleFailure 输出结构化 brief。
        try {
            if (descriptor != null && descriptor.risk() != ToolRisk.LOW
                    && !authGate.isSessionAllowed(toolName)) {
                AuthorizationGate.Decision decision = authGate.authorize(descriptor, toolName, argsMap);
                if (!decision.approved()) {
                    throw new ToolDeniedException(
                            ToolErrorCode.AUTHORIZATION_DENIED,
                            "user denied " + toolName + (decision.reason() == null ? "" : ": " + decision.reason()),
                            "调整计划或换工具");
                }
                if (decision.sessionWide()) {
                    authGate.rememberSessionAllow(toolName);
                }
            }
        } catch (ToolDeniedException denied) {
            long gateMs = System.currentTimeMillis() - start;
            return handleFailure(executionId, denied, toolName, stepIndex, gateMs, argsMap, signal, observers);
        }

        Instant invokedAt = Instant.now();
        emitActionInvoked(executionId, invokedAt, stepIndex, toolName, signal, observers);

        try {
            String result = retryPolicy.execute(
                    () -> invokeWithTimeout(cb, argsJson, timeoutMs, toolName),
                    classifier,
                    RetryPolicy.DEFAULT_SLEEPER,
                    (attempt, kind, cause, sleepMs) ->
                            log.debug("tool={} retry attempt={} kind={} sleep={}ms cause={}",
                                    toolName, attempt, kind, sleepMs, cause.toString()));
            long ms = System.currentTimeMillis() - start;

            // 阶段 5:工具结果不再单独存盘 —— 直接返回 result,不再追加 #id
            // 完整结果已写入 short-term.json(由 SessionMessageStore 在 tool 消息落盘时记录)
            emitObservation(executionId, stepIndex, toolName, ObservationEvent.Status.OK, result, ms, signal, observers);
            return result;
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - start;
            return handleFailure(executionId, t, toolName, stepIndex, ms, argsMap, signal, observers);
        }
    }

    /**
     * 把 cb.call 包在 CompletableFuture 里,用 future.get(timeoutMs) 强制超时。
     * 超时 → 抛 TimeoutException(由 FailureClassifier 判为 TRANSIENT,触发 RetryPolicy 重试)。
     * 取消 → future.cancel(true) 试图中断可中断 IO;不可中断的会跑满 timeout。
     */
    private String invokeWithTimeout(ToolCallback cb, String argsJson, long timeoutMs, String toolName) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> cb.call(argsJson), toolExecutor);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            // 包装为带 TIMEOUT 错误码的 ToolExecutionException,RetryPolicy 因 TRANSIENT 仍重试
            // 最终 brief 会写 "[error: TIMEOUT]"(而不是 [error: IO_TRANSIENT])
            throw new ToolExecutionException(
                    ToolErrorCode.TIMEOUT,
                    "tool " + toolName + " timed out after " + timeoutMs + "ms",
                    "缩短操作或调整工具超时",
                    FailureKind.TRANSIENT,
                    te);
        } catch (ExecutionException ee) {
            // 拆包:让 FailureClassifier 看到真实异常类型
            Throwable cause = ee.getCause();
            throw (cause instanceof Exception ex) ? ex : new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    private long resolveTimeoutMs(ToolDescriptor descriptor) {
        if (descriptor != null && descriptor.timeoutMs() > 0) {
            return descriptor.timeoutMs();
        }
        return properties.defaultTimeoutMs();
    }

    // 阶段 5:appendStoredId 删除 —— 工具结果不再单独存盘。完整结果走 short-term.json 全量记录。

    private String handleFailure(String executionId,
                                 Throwable t,
                                 String toolName,
                                 int stepIndex,
                                 long latencyMs,
                                 Map<String, Object> argsMap,
                                 ReActLoopSignal signal,
                                 List<ReActLoopObserver> observers) {
        FailureKind kind = classifier.classify(t);
        ToolResult result = toToolResult(t, kind, toolName);
        ObservationEvent.Status status = result.status() == ToolResult.Status.DENIED
                ? ObservationEvent.Status.DENIED
                : ObservationEvent.Status.ERROR;

        // LOGIC + 可逆 → 回滚已写入的副作用,提示 LLM 重新规划
        RollbackSummary rollback = null;
        if (kind == FailureKind.LOGIC) {
            ToolDescriptor descriptor = descriptorRegistry.get(toolName).orElse(null);
            if (descriptor != null && descriptor.reversible()) {
                rollback = sideEffects.rollbackAll();
                emitRollback(executionId, Instant.now(), stepIndex, toolName, rollback, signal, observers);
                log.warn("tool={} LOGIC failure → rollback rolledCount={} total={}",
                        toolName, rollback.rolledCount(), rollback.rolledTargets().size());
            }
        }

        String brief = briefForLlm(result, argsMap, rollback);
        log.warn("tool={} failed kind={} code={}: {}",
                toolName, kind,
                result.error() != null ? result.error().errorCode() : "?",
                t.getMessage());
        emitObservation(executionId, stepIndex, toolName, status, brief, latencyMs, signal, observers);
        return brief;
    }

    private ToolResult toToolResult(Throwable t, FailureKind kind, String toolName) {
        if (t instanceof ToolExecutionException tee) {
            return ToolResult.error(ToolError.of(
                    kind,
                    tee.getErrorCode(),
                    tee.getMessage(),
                    tee.getSuggestion()));
        }
        if (t instanceof ToolDeniedException denied) {
            // 沙箱拒绝 / 用户授权拒绝 —— DENIED 状态(不是 ERROR),briefForLlm 由 handleFailure 渲染
            return ToolResult.denied(ToolError.of(
                    kind,
                    denied.getErrorCode(),
                    denied.getMessage(),
                    denied.getSuggestion()));
        }
        ToolErrorCode code = kind == FailureKind.TRANSIENT
                ? ToolErrorCode.IO_TRANSIENT
                : ToolErrorCode.INTERNAL_ERROR;
        return ToolResult.error(ToolError.of(kind, code,
                "tool " + toolName + " failed: " + t.getMessage(),
                "调整参数或重试"));
    }

    private String formatUnknownTool(String name, Map<String, Object> argsMap) {
        ToolResult r = ToolResult.error(ToolError.of(
                FailureKind.PARAM,
                ToolErrorCode.INVALID_ARGUMENT,
                "unknown tool: " + name,
                "检查可用工具列表"));
        return briefForLlm(r, argsMap, null);
    }

    /**
     * 给 LLM 看的错误摘要。包含 errorCode + 日志(5+15 采样) + 建议 + 触发本次调用的参数。
     */
    String briefForLlm(ToolResult r, Map<String, Object> argsMap, RollbackSummary rollback) {
        if (r.error() == null) return r.content() == null ? "" : r.content();
        StringBuilder sb = new StringBuilder();
        if (rollback != null && !rollback.wasEmpty()) {
            sb.append("[logic-rollback] ");
        }
        sb.append("[error: ").append(r.error().errorCode()).append("] ");
        sb.append(sampleHeadTail(r.error().message(), 5, 15));
        if (r.error().suggestion() != null) {
            sb.append("\nsuggestion: ").append(r.error().suggestion());
        }
        if (rollback != null && !rollback.wasEmpty()) {
            sb.append("\nrolled-back:\n").append(rollback.render());
            sb.append("\naction: re-plan from current file state (above edits reversed)");
        } else if (rollback != null) {
            sb.append("\nrolled-back: (none tracked; this tool may have caused damage elsewhere — check git status)");
            sb.append("\naction: re-plan from current state");
        }
        if (argsMap != null && !argsMap.isEmpty()) {
            sb.append("\nargs: ").append(renderArgs(argsMap));
        }
        return sb.toString();
    }

    String briefForLlm(ToolResult r, Map<String, Object> argsMap) {
        return briefForLlm(r, argsMap, null);
    }

    static String sampleHeadTail(String text, int head, int tail) {
        if (text == null || text.isEmpty()) return "";
        if (head < 0 || tail < 0) return text;
        String[] lines = text.split("\\r?\\n", -1);
        if (lines.length <= head + tail) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        int skipped = lines.length - head - tail;
        sb.append("\n...(省略 ").append(skipped).append(" 行)...\n");
        for (int i = 0; i < tail; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[lines.length - tail + i]);
        }
        return sb.toString();
    }

    static String renderArgs(Map<String, Object> args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(args);
        } catch (Exception ex) {
            return args.toString();
        }
    }

    // ============== 事件发射 ==============

    private void emitPreCheck(String executionId, Instant at, int step, String toolName,
                              Map<String, Object> args,
                              ReActLoopSignal signal,
                              List<ReActLoopObserver> observers) {
        ActionPreCheckEvent ev = ActionPreCheckEvent.builder()
                .executionId(executionId)
                .at(at)
                .stepIndex(step)
                .agentName("tool-gateway")
                .toolName(toolName)
                .args(args)
                .build();
        safeDispatch(observers, o -> o.onActionPreCheck(ev, signal));
    }

    private void emitActionInvoked(String executionId, Instant at, int step, String toolName,
                                   ReActLoopSignal signal,
                                   List<ReActLoopObserver> observers) {
        ActionInvokedEvent ev = ActionInvokedEvent.builder()
                .executionId(executionId)
                .at(at)
                .stepIndex(step)
                .agentName("tool-gateway")
                .toolName(toolName)
                .build();
        safeDispatch(observers, o -> o.onActionInvoked(ev, signal));
    }

    private void emitObservation(String executionId, int step, String toolName,
                                 ObservationEvent.Status status,
                                 String text, long latencyMs,
                                 ReActLoopSignal signal,
                                 List<ReActLoopObserver> observers) {
        ObservationEvent ev = ObservationEvent.builder()
                .executionId(executionId)
                .at(Instant.now())
                .stepIndex(step)
                .agentName("tool-gateway")
                .toolName(toolName)
                .status(status)
                .observationText(text == null ? "" : truncate(text, 4000))
                .latencyMs(latencyMs)
                .build();
        safeDispatch(observers, o -> o.onObservation(ev, signal));
    }

    private void emitRollback(String executionId, Instant at, int step, String toolName,
                              RollbackSummary rollback,
                              ReActLoopSignal signal,
                              List<ReActLoopObserver> observers) {
        RollbackEvent ev = RollbackEvent.builder()
                .executionId(executionId)
                .at(at)
                .stepIndex(step)
                .agentName("tool-gateway")
                .toolName(toolName)
                .rolledCount(rollback == null ? 0 : rollback.rolledCount())
                .rolledTargets(rollback == null ? java.util.List.of() : rollback.rolledTargets())
                .build();
        safeDispatch(observers, o -> o.onRollback(ev, signal));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(truncated)";
    }

    private static void safeDispatch(List<ReActLoopObserver> observers,
                                     java.util.function.Consumer<ReActLoopObserver> invoker) {
        for (ReActLoopObserver o : observers) {
            try {
                invoker.accept(o);
            } catch (Exception ex) {
                log.warn("observer dispatch failed: {}", ex.getMessage());
            }
        }
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("_raw", json);
            return fallback;
        }
    }

    /**
     * 全放行授权闸。仅用于旧测试兼容 —— {@link #ToolGateway(ToolCallbackProvider, ToolDescriptorRegistry, FailureClassifier, RetryPolicy, SideEffectTracker)}
     * 五参构造器默认注入本闸，避免新逻辑拦截历史用例。
     */
    public static final class AllowAllAuthorizationGate implements AuthorizationGate {
        public static final AllowAllAuthorizationGate INSTANCE = new AllowAllAuthorizationGate();
        private final Set<String> allowed = ConcurrentHashMap.newKeySet();
        @Override public Decision authorize(ToolDescriptor descriptor, String toolName, Map<String, Object> args) { return Decision.allowOnce(); }
        @Override public boolean isSessionAllowed(String toolName) { return allowed.contains(toolName); }
        @Override public void rememberSessionAllow(String toolName) { if (toolName != null) allowed.add(toolName); }
    }
}

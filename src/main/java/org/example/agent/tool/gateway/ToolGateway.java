package org.example.agent.tool.gateway;

import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.RollbackEvent;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.signal.ReActLoopSignal;
import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.failure.FailureClassifier;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.RetryPolicy;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.result.ToolError;
import org.example.agent.tool.result.ToolResult;
import org.example.agent.tool.rollback.RollbackSummary;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工具调用的中央入口。
 *
 * <p>ReActLoop 拿到 LLM 的 tool_calls 后，循环调用本类的 {@link #invoke}。
 * 本类负责：
 * <ol>
 *   <li>查 {@link ToolDescriptorRegistry} 拿元数据（risk / reversible）</li>
 *   <li>发 {@link ActionPreCheckEvent}</li>
 *   <li>查 Spring AI {@link ToolCallback} by name</li>
 *   <li>发 {@link ActionInvokedEvent}</li>
 *   <li>用 {@link RetryPolicy} 包裹真实调用</li>
 *   <li>异常时用 {@link FailureClassifier} 分类，包装成结构化错误返回 LLM</li>
 *   <li>发 {@link ObservationEvent}</li>
 * </ol>
 *
 * <p>沙箱校验（路径闸 / 命令闸）由 {@code @Tool} 方法自己调用，本类不做。
 * 理由：{@code @Tool} 方法直接接触路径 / 命令参数，校验就近做最自然；
 * 工具作者忘加的风险由 descriptor 的 risk 等级 + v2 授权 UI 兜底。
 */
@Component
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    private final Map<String, ToolCallback> callbacks;
    private final ToolDescriptorRegistry descriptorRegistry;
    private final FailureClassifier classifier;
    private final RetryPolicy retryPolicy;
    private final SideEffectTracker sideEffects;

    public ToolGateway(ToolCallbackProvider toolCallbackProvider,
                       ToolDescriptorRegistry descriptorRegistry,
                       FailureClassifier classifier,
                       RetryPolicy retryPolicy,
                       SideEffectTracker sideEffects) {
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
    }

    public Optional<ToolCallback> lookup(String name) {
        return Optional.ofNullable(callbacks.get(name));
    }

    /**
     * 执行一次工具调用。返回 LLM 看到的响应（成功时是工具输出，失败时是结构化错误摘要）。
     *
     * <p>不会向上抛异常 —— 所有失败都被翻译成结构化错误字符串。
     *
     * @param executionId 当前执行 id（写进事件）
     * @param toolName    工具名（{@code @Tool.name} 或方法名）
     * @param argsJson    LLM 传来的参数 JSON 字符串
     * @param stepIndex   当前 step 序号（用于事件）
     * @param signal      协作式终止信号
     * @param observers   事件订阅者
     * @return LLM 看到的响应
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

        Instant invokedAt = Instant.now();
        emitActionInvoked(executionId, invokedAt, stepIndex, toolName, signal, observers);
        long start = System.currentTimeMillis();

        try {
            String result = retryPolicy.execute(
                    () -> cb.call(argsJson),
                    classifier,
                    RetryPolicy.DEFAULT_SLEEPER,
                    (attempt, kind, cause, sleepMs) ->
                            log.debug("tool={} retry attempt={} kind={} sleep={}ms cause={}",
                                    toolName, attempt, kind, sleepMs, cause.toString()));
            long ms = System.currentTimeMillis() - start;
            emitObservation(executionId, stepIndex, toolName, ObservationEvent.Status.OK, result, ms, signal, observers);
            return result;
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - start;
            return handleFailure(executionId, t, toolName, stepIndex, ms, argsMap, signal, observers);
        }
    }

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
     * 完整 stack trace 仍在 audit log。
     *
     * <p>LOGIC 失败 + 已回滚时: 在常规 [error: CODE] 之前插入 {@code [logic-rollback]} 头,
     * 并多输出两段 {@code rolled-back:} 与 {@code action: re-plan from current state},
     * 告诉 LLM 上一轮写入已被撤销,需要重新规划。
     *
     * <p>package-private 是为了同包测试直接调,无需反射 / Mockito。
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

    /**
     * 兼容旧签名 —— 不带 rollback 的 brief (PARAM/TRANSIENT 路径)。
     * package-private 方便测试。
     */
    String briefForLlm(ToolResult r, Map<String, Object> argsMap) {
        return briefForLlm(r, argsMap, null);
    }

    /**
     * 非对称头尾采样: 保留首 head 行 + 末 tail 行,中间用 "(省略 N 行)" 占位。
     * 行数 <= head+tail 时原样返回(不画蛇添足)。
     * package-private 是为了在同包测试里直接打桩验证行数 / 占位文本 / 头尾保留。
     */
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
        // 简单解析 —— Spring AI 内部会用 jsonschema 做严格解析，这里只需要给事件一个大致可读的视图
        // 真正的类型转换由 ToolCallback 完成
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception ex) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("_raw", json);
            return fallback;
        }
    }
}

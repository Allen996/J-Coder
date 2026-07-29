package org.example.agent.core.loop;

import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.kind.StepKind;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.record.StepRecord;
import org.example.agent.core.signal.ReActLoopSignal;
import org.example.agent.core.task.AgentTask;
import org.example.agent.tool.cache.ToolResultStore;
import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.gateway.ToolGateway;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 ReAct 循环的执行单元。
 *
 * <p>Part 2 改造：把 tool_call 循环纳入本类，绕过 Spring AI 的
 * {@code internalToolExecutionEnabled} 自动执行，由 {@link ToolGateway} 统一处理
 * 沙箱 / 重试 / 事件。
 *
 * <p>循环形态：
 * <pre>
 *   messages = [system, user]
 *   loop:
 *     response = chatModel.call(prompt(messages, options))
 *     assistant = response.getResult().getOutput()
 *     if not assistant.hasToolCalls():
 *         fullAnswer = assistant.getText()
 *         break
 *     messages.add(assistant)
 *     # 分桶:readonly 走并发;write 走串行(保留 SideEffectTracker 顺序语义)
 *     readonlyCalls, writeCalls = partition(assistant.getToolCalls())
 *     for writeCalls: toolGateway.invoke(serial)  → toolResponses
 *     allOf([toolGateway.invoke(async) for readonlyCalls])  → toolResponses
 *     messages.add(new ToolResponseMessage(toolResponses))
 *     if signal.isTerminateRequested(): break
 * </pre>
 *
 * <p>step 计数由 LoopStepObserver 接管（每次 gateway.emitObservation 触发 +1）。
 */
public class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    /** 兜底：单次执行最多调模型 16 次，防止 tool_call 互相调用导致的无限循环。 */
    private static final int MAX_MODEL_ITERATIONS = 16;

    /** auto-inline 时扫描最近 N 条消息。3 轮 ≈ user + assistant + tool_response × 3。 */
    private static final int AUTO_INLINE_SCAN_WINDOW = 6;

    private final String executionId;
    private final ChatModel chatModel;
    private final AgentTask task;
    private final String agentName;
    private final AgentBudget budget;
    private final ToolGateway toolGateway;
    private final List<ToolCallback> toolCallbacks;
    private final SideEffectTracker sideEffects;
    private final ContextBuilder contextBuilder;
    private final CliToolProperties properties;
    private final ToolDescriptorRegistry descriptorRegistry;
    private final ExecutorService toolExecutor;
    private final ToolResultStore resultStore;

    /** 兼容入口:缺少新增强依赖(用于纯单元测试场景,不走 invoke 路径)。 */
    public ReActLoop(String executionId,
                     ChatModel chatModel,
                     AgentTask task,
                     AgentBudget budget,
                     ToolGateway toolGateway,
                     List<ToolCallback> toolCallbacks,
                     SideEffectTracker sideEffects) {
        this(executionId, chatModel, task, budget, toolGateway, toolCallbacks, sideEffects,
                null, null, null, null, null);
    }

    /** 兼容入口:带 ContextBuilder 但无增强依赖。 */
    public ReActLoop(String executionId,
                     ChatModel chatModel,
                     AgentTask task,
                     AgentBudget budget,
                     ToolGateway toolGateway,
                     List<ToolCallback> toolCallbacks,
                     SideEffectTracker sideEffects,
                     ContextBuilder contextBuilder) {
        this(executionId, chatModel, task, budget, toolGateway, toolCallbacks, sideEffects,
                contextBuilder, null, null, null, null);
    }

    /** 完整构造器。Spring 生产路径走这里。 */
    public ReActLoop(String executionId,
                     ChatModel chatModel,
                     AgentTask task,
                     AgentBudget budget,
                     ToolGateway toolGateway,
                     List<ToolCallback> toolCallbacks,
                     SideEffectTracker sideEffects,
                     ContextBuilder contextBuilder,
                     CliToolProperties properties,
                     ToolDescriptorRegistry descriptorRegistry,
                     ExecutorService toolExecutor,
                     ToolResultStore resultStore) {
        this.executionId = executionId;
        this.chatModel = chatModel;
        this.task = task;
        this.agentName = safeName(task.getRole(), "intelligent_assistant");
        this.budget = budget;
        this.toolGateway = toolGateway;
        this.toolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
        this.sideEffects = sideEffects;
        this.contextBuilder = contextBuilder;
        this.properties = properties;
        this.descriptorRegistry = descriptorRegistry;
        this.toolExecutor = toolExecutor;
        this.resultStore = resultStore;
        log.info("executionId={} ReActLoop initialized with {} tool callbacks: {}",
                executionId,
                this.toolCallbacks.size(),
                this.toolCallbacks.stream().map(cb -> cb.getToolDefinition().name()).toList());
    }

    public String getExecutionId() { return executionId; }
    public ChatModel getChatModel() { return chatModel; }
    public AgentTask getTask() { return task; }
    public String getAgentName() { return agentName; }
    public AgentBudget getBudget() { return budget; }

    public record SubscribeResult(List<StepRecord> records, String fullAnswer, List<Message> turnMessages) {
    }

    public SubscribeResult subscribe(String input,
                                     Map<String, Object> toolArgsMap,
                                     List<ReActLoopObserver> observers,
                                     ReActLoopSignal signal) {
        AtomicInteger stepCounter = new AtomicInteger(0);
        AtomicLong lastStepStart = new AtomicLong(System.currentTimeMillis());
        List<StepRecord> records = Collections.synchronizedList(new ArrayList<>());
        StringBuilder fullAnswer = new StringBuilder();
        List<Message> messages = new ArrayList<>(buildInitialMessages(input));
        List<Message> turnMessages = new ArrayList<>();
        if (input != null && !input.isEmpty()) {
            turnMessages.add(new UserMessage(input));
        }

        // 绑定副作用追踪器到当前 executionId —— FileTools 之后写入文件会在这里登记。
        // 失败路径触发的 rollbackAll() 也读这个 ThreadLocal,所以必须在循环入口 bind,出口 clear。
        sideEffects.bind(executionId);
        try {
            for (int iter = 0; iter < MAX_MODEL_ITERATIONS; iter++) {
                if (signal.isTerminateRequested()) {
                    log.info("executionId={} signal terminate before iter={}", executionId, iter);
                    break;
                }

                // 每次 LLM 调用前通知 observer 当前的 prompt（part3.md 可观测增强）
                int promptStep = stepCounter.get() + 1;
                emitPromptBuilt(observers, messages, promptStep, signal);

                // Auto-inline:扫描最近消息里的 #<id> 占位符,尝试从外置存储召回并嵌入
                autoInlinePlaceholders(messages);

                Prompt prompt = buildPrompt(messages);
                ChatResponse response = chatModel.call(prompt);
                if (response == null) {
                    throw new IllegalStateException("ChatModel.call returned null response");
                }
                AssistantMessage assistant = response.getResult() == null
                        ? null : response.getResult().getOutput();
                if (assistant == null) {
                    throw new IllegalStateException("ChatModel.call returned no output");
                }

                String text = assistant.getText();
                long promptTokens = 0L;
                long completionTokens = 0L;
                if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                    var u = response.getMetadata().getUsage();
                    promptTokens = u.getPromptTokens() == null ? 0L : u.getPromptTokens();
                    completionTokens = u.getCompletionTokens() == null ? 0L : u.getCompletionTokens();
                }
                Instant at = Instant.now();
                int step = stepCounter.incrementAndGet();
                lastStepStart.set(System.currentTimeMillis());

                emitThought(observers, buildThoughtEvent(at, step, text, promptTokens, completionTokens), signal);
                records.add(StepRecord.builder()
                        .stepIndex(step)
                        .kind(StepKind.THOUGHT)
                        .at(at)
                        .agentName(agentName)
                        .thoughtSummary(text)
                        .latencyMs(System.currentTimeMillis() - lastStepStart.get())
                        .tokensConsumed(promptTokens + completionTokens)
                        .build());

                log.info("executionId={} model response: hasToolCalls={} toolCallCount={} text={}",
                        executionId,
                        assistant.hasToolCalls(),
                        assistant.hasToolCalls() ? assistant.getToolCalls().size() : 0,
                        text == null ? "<null>" : (text.length() > 200 ? text.substring(0, 200) + "..." : text));
                if (!assistant.hasToolCalls()) {
                    turnMessages.add(assistant);
                    if (text != null) {
                        fullAnswer.append(text);
                    }
                    break;
                }

                messages.add(assistant);
                turnMessages.add(assistant);

                List<ToolResponseMessage.ToolResponse> toolResponses =
                        dispatchToolCalls(assistant.getToolCalls(), executionId, step, signal, observers);
                if (!toolResponses.isEmpty()) {
                    ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                            .responses(toolResponses)
                            .build();
                    messages.add(toolMessage);
                    turnMessages.add(toolMessage);
                }
            }
        } catch (Exception ex) {
            log.error("executionId={} call failed", executionId, ex);
            throw new IllegalStateException("ChatModel.call failed: " + ex.getMessage(), ex);
        } finally {
            sideEffects.clear();
        }

        return new SubscribeResult(
                records,
                fullAnswer.toString(),
                Collections.unmodifiableList(new ArrayList<>(turnMessages)));
    }

    /**
     * 分桶 + 并发分发工具调用:
     * <ul>
     *   <li>readonly 工具 → CompletableFuture 并发(若 cli.tool.parallel=true 且 ≥2 个)</li>
     *   <li>write 工具 → 串行(保留 SideEffectTracker 顺序语义)</li>
     * </ul>
     *
     * <p>toolExecutor 为 null 时(测试路径)全部串行 —— 旧行为。
     */
    private List<ToolResponseMessage.ToolResponse> dispatchToolCalls(
            List<AssistantMessage.ToolCall> calls,
            String executionId,
            int step,
            ReActLoopSignal signal,
            List<ReActLoopObserver> observers) {

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>(calls.size());

        List<AssistantMessage.ToolCall> writeCalls = new ArrayList<>();
        List<AssistantMessage.ToolCall> readonlyCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : calls) {
            if (isReadonly(tc.name())) {
                readonlyCalls.add(tc);
            } else {
                writeCalls.add(tc);
            }
        }

        // 写工具一律串行(顺序语义)
        for (AssistantMessage.ToolCall tc : writeCalls) {
            if (signal.isTerminateRequested()) break;
            String result = toolGateway.invoke(executionId, tc.name(), tc.arguments(), step, signal, observers);
            toolResponses.add(new ToolResponseMessage.ToolResponse(
                    tc.id(), tc.name(), result == null ? "" : result));
        }

        // 只读工具:开关关 / <2 个 / toolExecutor 缺失 → 串行
        boolean parallel = properties != null && properties.parallel()
                && toolExecutor != null
                && readonlyCalls.size() > 1;
        if (!parallel) {
            for (AssistantMessage.ToolCall tc : readonlyCalls) {
                if (signal.isTerminateRequested()) break;
                String result = toolGateway.invoke(executionId, tc.name(), tc.arguments(), step, signal, observers);
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        tc.id(), tc.name(), result == null ? "" : result));
            }
            return toolResponses;
        }

        // 并发路径
        log.debug("executionId={} dispatching {} readonly tool calls in parallel", executionId, readonlyCalls.size());
        List<CompletableFuture<ToolResponseMessage.ToolResponse>> futures = new ArrayList<>(readonlyCalls.size());
        for (AssistantMessage.ToolCall tc : readonlyCalls) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> new ToolResponseMessage.ToolResponse(
                            tc.id(), tc.name(),
                            toolGateway.invoke(executionId, tc.name(), tc.arguments(), step, signal, observers)),
                    toolExecutor));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .join();   // 单个失败由 toolGateway.invoke 内部翻译为错误 brief,future 仍正常完成
        } catch (Exception ex) {
            log.warn("executionId={} parallel dispatch join failed: {}", executionId, ex.getMessage());
        }
        for (CompletableFuture<ToolResponseMessage.ToolResponse> f : futures) {
            try {
                toolResponses.add(f.join());
            } catch (Exception ex) {
                // 兜底:future 异常不应出现(toolGateway.invoke 不抛),但出现时不污染整体
                log.warn("executionId={} parallel future join error: {}", executionId, ex.getMessage());
            }
        }
        return toolResponses;
    }

    /** 工具是否 readonly(可在 ReActLoop 中并发分发)。未知工具默认写语义(串行)。 */
    private boolean isReadonly(String toolName) {
        if (descriptorRegistry == null) return false;
        return descriptorRegistry.get(toolName)
                .map(ToolDescriptor::readonly)
                .orElse(false);
    }

    /**
     * 扫描 messages 末尾 N 条 user/assistant 文本中的 {@code #<id>} 占位符,
     * 尝试从外置存储召回并 inline,直到预算耗尽。
     * 预算耗尽后剩余 id 改 inline 元数据提示。
     *
     * <p>变更通过追加一个 SystemMessage 体现(沿用 ConversationCompressor 的同模式)。
     */
    private void autoInlinePlaceholders(List<Message> messages) {
        if (properties == null || resultStore == null) return;
        if (!properties.resultCache().enabled()) return;
        int budget = properties.resultCache().autoInlineByteBudget();
        if (budget <= 0) return;

        int scanFrom = Math.max(0, messages.size() - AUTO_INLINE_SCAN_WINDOW);
        StringBuilder scanned = new StringBuilder();
        for (int i = scanFrom; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m instanceof UserMessage um && um.getText() != null) {
                scanned.append(um.getText()).append('\n');
            } else if (m instanceof AssistantMessage am && am.getText() != null) {
                scanned.append(am.getText()).append('\n');
            }
        }
        List<String> ids = resultStore.scanIds(scanned.toString());
        if (ids.isEmpty()) return;

        List<String> inlined = new ArrayList<>();
        int used = 0;
        for (String id : ids) {
            ToolResultStore.RecallResult rec = resultStore.recall(id, null, null, null);
            if (rec instanceof ToolResultStore.RecallResult.Ok ok) {
                int size = ok.content() == null ? 0 : ok.content().length();
                if (used + size <= budget) {
                    inlined.add("#" + id + ":\n" + ok.content());
                    used += size;
                } else {
                    inlined.add(resultStore.metadataHint(id));
                }
            } else {
                // EXPIRED / STALE_REMOVED / NOT_FOUND / INVALID —— 跳过,不要污染 prompt
                log.debug("autoInline skip id={} reason={}", id, rec.getClass().getSimpleName());
            }
        }
        if (inlined.isEmpty()) return;
        messages.add(new SystemMessage("[auto-recalled tool results]\n" + String.join("\n\n", inlined)));
    }

    // ============== 内部 ==============

    private List<Message> buildInitialMessages(String input) {
        if (contextBuilder != null) {
            try {
                ContextBuilder.BuiltContext built = contextBuilder.build(task, input);
                log.debug("executionId={} ContextBuilder assembled {} messages (static={} dynamic={} total={}, dynamic budget={})",
                        executionId, built.getMessages().size(),
                        built.getStaticTokens(), built.getDynamicTokens(),
                        built.getTotalTokens(), built.getDynamicReserved());
                return new ArrayList<>(built.getMessages());
            } catch (ContextBuilder.ContextOverflowException ex) {
                log.warn("executionId={} ContextBuilder overflow at build: used={} reserved={}",
                        executionId, ex.getUsed(), ex.getReserved());
                List<Message> fallback = new ArrayList<>();
                fallback.add(new SystemMessage("（上下文超限，无法继续）"));
                fallback.add(new UserMessage(input));
                return fallback;
            }
        }
        List<Message> msgs = new ArrayList<>();
        Map<String, Object> vars = new HashMap<>();
        if (task.getPromptVariables() != null) {
            vars.putAll(task.getPromptVariables());
        }
        vars.put("input", input);
        if (task.getSessionId() != null) vars.put("sessionId", task.getSessionId());
        if (task.getRole() != null) vars.put("role", task.getRole());

        StringBuilder sys = new StringBuilder();
        sys.append("你是一个专业的智能助手，能调用工具回答用户问题。\n");
        sys.append("当前任务上下文：\n");
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            sys.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        if (task.getToolAllowList() != null && !task.getToolAllowList().isEmpty()) {
            sys.append("可用工具：").append(String.join(", ", task.getToolAllowList())).append("\n");
        } else if (!toolCallbacks.isEmpty()) {
            sys.append("你可以使用以下工具，必要时主动调用。\n");
        }
        msgs.add(new SystemMessage(sys.toString()));
        msgs.add(new UserMessage(input));
        return msgs;
    }

    private Prompt buildPrompt(List<Message> messages) {
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)
                .build();
        return new Prompt(messages, options);
    }

    private ThoughtEvent buildThoughtEvent(Instant at, int step, String text,
                                           long promptTokens, long completionTokens) {
        return ThoughtEvent.builder()
                .executionId(executionId)
                .at(at)
                .stepIndex(step)
                .agentName(agentName)
                .thoughtText(text)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .build();
    }

    private void emitThought(List<ReActLoopObserver> observers, ThoughtEvent e, ReActLoopSignal s) {
        for (ReActLoopObserver o : observers) {
            try { o.onThought(e, s); } catch (Exception ex) { log.warn("observer onThought failed", ex); }
        }
    }

    private void emitPromptBuilt(List<ReActLoopObserver> observers, List<Message> messages, int stepIndex, ReActLoopSignal s) {
        for (ReActLoopObserver o : observers) {
            try {
                o.onPromptBuilt(new ArrayList<>(messages), stepIndex);
            } catch (Exception ex) {
                log.warn("observer onPromptBuilt failed", ex);
            }
        }
    }

    private static String safeName(String role, String fallback) {
        return (role == null || role.isBlank()) ? fallback : role;
    }
}

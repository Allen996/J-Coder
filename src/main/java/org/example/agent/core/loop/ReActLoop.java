package org.example.agent.core.loop;

import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.kind.StepKind;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.record.StepRecord;
import org.example.agent.core.signal.ReActLoopSignal;
import org.example.agent.core.task.AgentTask;
import org.example.agent.tool.gateway.ToolGateway;
import org.example.agent.tool.rollback.SideEffectTracker;
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
 *     for each toolCall in assistant.getToolCalls():
 *         result = toolGateway.invoke(name, args, step, signal, observers)
 *         toolResponses.add(new ToolResponse(toolCall.id, name, result))
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

    private final String executionId;
    private final ChatModel chatModel;
    private final AgentTask task;
    private final String agentName;
    private final AgentBudget budget;
    private final ToolGateway toolGateway;
    private final List<ToolCallback> toolCallbacks;
    private final SideEffectTracker sideEffects;
    private final ContextBuilder contextBuilder;

    public ReActLoop(String executionId,
                     ChatModel chatModel,
                     AgentTask task,
                     AgentBudget budget,
                     ToolGateway toolGateway,
                     List<ToolCallback> toolCallbacks,
                     SideEffectTracker sideEffects) {
        this(executionId, chatModel, task, budget, toolGateway, toolCallbacks, sideEffects, null);
    }

    public ReActLoop(String executionId,
                     ChatModel chatModel,
                     AgentTask task,
                     AgentBudget budget,
                     ToolGateway toolGateway,
                     List<ToolCallback> toolCallbacks,
                     SideEffectTracker sideEffects,
                     ContextBuilder contextBuilder) {
        this.executionId = executionId;
        this.chatModel = chatModel;
        this.task = task;
        this.agentName = safeName(task.getRole(), "intelligent_assistant");
        this.budget = budget;
        this.toolGateway = toolGateway;
        this.toolCallbacks = toolCallbacks == null ? List.of() : toolCallbacks;
        this.sideEffects = sideEffects;
        this.contextBuilder = contextBuilder;
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

                // 任何模型输出都先 emit ThoughtEvent（即使后面有 tool_calls）
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

                // 路径分叉：tool_calls vs 最终回答
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

                // 把 AssistantMessage（含 tool_calls）加进历史，模型下一轮能看见自己刚才要调什么
                messages.add(assistant);
                turnMessages.add(assistant);

                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                    if (signal.isTerminateRequested()) {
                        break;
                    }
                    String result = toolGateway.invoke(
                            executionId, tc.name(), tc.arguments(), step, signal, observers);
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            tc.id(), tc.name(), result == null ? "" : result));
                }
                if (!toolResponses.isEmpty()) {
                    ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                            .responses(toolResponses)
                            .build();
                    messages.add(toolMessage);
                    turnMessages.add(toolMessage);

                    // TODO(v2): 上下文窗口压缩 —— 当出现 LOGIC + rollback 时,本轮 (Assistant + ToolResponse)
                    // 已被标记为"已撤销的历史",下一轮 prompt 应当把它折叠成单行摘要,而不是原样塞进 messages。
                    // 触发现条件: toolResponses 里只要有一个状态由 ToolGateway 回包成
                    // [logic-rollback] 头,就调用 ContextCompressionHook.compressAfterRollback(messages, ...)
                    // 把它压成一个 ≤ N token 的 "rollback summary" 行塞回去。
                    // 当前 v1 简化:不压缩,完整历史会一直累积直到 budget 上限触发 FINISH。
                }
            }
        } catch (Exception ex) {
            log.error("executionId={} call failed", executionId, ex);
            throw new IllegalStateException("ChatModel.call failed: " + ex.getMessage(), ex);
        } finally {
            // 出口清理 —— 即便发生异常也不留 orphan session
            sideEffects.clear();
        }

        return new SubscribeResult(
                records,
                fullAnswer.toString(),
                Collections.unmodifiableList(new ArrayList<>(turnMessages)));
    }

    // ============== 内部 ==============

    private List<Message> buildInitialMessages(String input) {
        // Part 3 改造：交给 ContextBuilder 三层装配；contextBuilder 为 null 时降级到 Part 1 模板。
        if (contextBuilder != null) {
            try {
                ContextBuilder.BuiltContext built = contextBuilder.build(task, input);
                log.debug("executionId={} ContextBuilder assembled {} messages (static={} dynamic={} total={}, dynamic budget={})",
                        executionId, built.getMessages().size(),
                        built.getStaticTokens(), built.getDynamicTokens(),
                        built.getTotalTokens(), built.getDynamicReserved());
                return new ArrayList<>(built.getMessages());
            } catch (ContextBuilder.ContextOverflowException ex) {
                // 装配阶段已经超预算 —— 让 ReActLoop 进入下一轮立即被 TokenBudgetObserver 终结。
                log.warn("executionId={} ContextBuilder overflow at build: used={} reserved={}",
                        executionId, ex.getUsed(), ex.getReserved());
                // 仍要返回至少 [system, user] 否则 chatModel.call 会失败
                List<Message> fallback = new ArrayList<>();
                fallback.add(new SystemMessage("（上下文超限，无法继续）"));
                fallback.add(new UserMessage(input));
                return fallback;
            }
        }
        // ---- 兜底 ----
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

    /** 派发 prompt-build 事件（part3.md 可观测增强），用于 /verbose 实时打印与 /context 当前快照。 */
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

package org.example.agent.core.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextAwareAgentBudgetFactory;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.compression.AutoCompressionObserver;
import org.example.agent.context.memory.LongTermMaintainer;
import org.example.agent.context.memory.MemoryTurnHook;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.event.AgentEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.handle.AgentHandle;
import org.example.agent.core.loop.ReActLoop;
import org.example.agent.core.observer.EventRecordingObserver;
import org.example.agent.core.observer.LoopStepObserver;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.observer.SinkEmittingObserver;
import org.example.agent.core.observer.TimeoutObserver;
import org.example.agent.core.observer.TokenBudgetObserver;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.record.AgentExecutionRecord;
import org.example.agent.core.registry.ExecutionRegistry;
import org.example.agent.core.result.AgentExecutionResult;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.signal.DefaultReActLoopSignal;
import org.example.agent.core.task.AgentTask;
import org.example.agent.tool.gateway.ToolGateway;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AgentRuntime 的标准实现。
 *
 * 线程模型：
 *  - execute() 默认在调用线程同步跑（管理 budget + observer 副作用方便）
 *  - stream() 返回 Flux，每条事件通过 sink 推到订阅方
 *  - cancel() 走 ExecutionRegistry，找到对应 handle 触发 cancelAction
 *
 * Observer 注入：
 *  - 业务方通过 {@link #registerObserver(ReActLoopObserver)} 注册全局观察者
 *  - AgentRuntimeImpl 在每次执行链里再克隆一次，叠上本实例内部的
 *    TokenBudgetObserver / LoopStepObserver / TimeoutObserver（每次执行独立，
 *    因为它们的累加器是 per-execution 的）
 */
@Slf4j
@Component
public class AgentRuntimeImpl implements AgentRuntime {

    private final ChatModel chatModel;
    private final ToolGateway toolGateway;
    private final ToolCallbackProvider toolCallbackProvider;
    private final SideEffectTracker sideEffectTracker;
    private final ExecutionRegistry registry;
    private final List<ReActLoopObserver> observers = new CopyOnWriteArrayList<>();
    private final ExecutorService blockingExecutor;
    private final ContextBuilder contextBuilder;
    private final AutoCompressionObserver autoCompressionObserver;
    private final SessionMessageStore sessionStore;
    private final ContextAwareAgentBudgetFactory budgetFactory;
    private final MemoryTurnHook memoryTurnHook;
    private final LongTermMaintainer longTermMaintainer;
    private final org.example.agent.tool.cache.ToolResultStore resultStore;
    private final org.example.agent.tool.config.CliToolProperties cliToolProperties;
    private final org.example.agent.tool.spi.ToolDescriptorRegistry toolDescriptorRegistry;
    private final ExecutorService toolExecutor;

    @Autowired
    public AgentRuntimeImpl(ChatModel chatModel,
                            ToolGateway toolGateway,
                            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
                            SideEffectTracker sideEffectTracker,
                            ContextBuilder contextBuilder,
                            AutoCompressionObserver autoCompressionObserver,
                            SessionMessageStore sessionStore,
                            ContextAwareAgentBudgetFactory budgetFactory,
                            MemoryTurnHook memoryTurnHook,
                            LongTermMaintainer longTermMaintainer,
                            org.example.agent.tool.cache.ToolResultStore resultStore,
                            org.example.agent.tool.config.CliToolProperties cliToolProperties,
                            org.example.agent.tool.spi.ToolDescriptorRegistry toolDescriptorRegistry,
                            @org.springframework.beans.factory.annotation.Qualifier("toolExecutor")
                            ExecutorService toolExecutor) {
        this(chatModel, toolGateway, toolCallbackProvider, sideEffectTracker,
                contextBuilder, autoCompressionObserver, sessionStore,
                budgetFactory, memoryTurnHook, longTermMaintainer, resultStore,
                cliToolProperties, toolDescriptorRegistry, toolExecutor,
                defaultBlockingExecutor());
    }

    /**
     * 测试 / 旧装配入口 —— 不带记忆钩子;记忆相关操作全部走 null-safe noop。
     */
    public AgentRuntimeImpl(ChatModel chatModel,
                            ToolGateway toolGateway,
                            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
                            SideEffectTracker sideEffectTracker,
                            ContextBuilder contextBuilder,
                            AutoCompressionObserver autoCompressionObserver,
                            SessionMessageStore sessionStore,
                            ContextAwareAgentBudgetFactory budgetFactory,
                            ExecutorService blockingExecutor) {
        this(chatModel, toolGateway, toolCallbackProvider, sideEffectTracker,
                contextBuilder, autoCompressionObserver, sessionStore,
                budgetFactory, null, null, null, null, null, null, blockingExecutor);
    }

    public AgentRuntimeImpl(ChatModel chatModel,
                            ToolGateway toolGateway,
                            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
                            SideEffectTracker sideEffectTracker,
                            ContextBuilder contextBuilder,
                            AutoCompressionObserver autoCompressionObserver,
                            SessionMessageStore sessionStore,
                            ContextAwareAgentBudgetFactory budgetFactory,
                            MemoryTurnHook memoryTurnHook,
                            LongTermMaintainer longTermMaintainer,
                            org.example.agent.tool.cache.ToolResultStore resultStore,
                            org.example.agent.tool.config.CliToolProperties cliToolProperties,
                            org.example.agent.tool.spi.ToolDescriptorRegistry toolDescriptorRegistry,
                            ExecutorService toolExecutor,
                            ExecutorService blockingExecutor) {
        this.chatModel = chatModel;
        this.toolGateway = toolGateway;
        this.toolCallbackProvider = toolCallbackProvider == null ? null : toolCallbackProvider.getIfAvailable();
        this.sideEffectTracker = sideEffectTracker;
        this.contextBuilder = contextBuilder;
        this.autoCompressionObserver = autoCompressionObserver;
        this.sessionStore = sessionStore;
        this.budgetFactory = budgetFactory;
        this.memoryTurnHook = memoryTurnHook;
        this.longTermMaintainer = longTermMaintainer;
        this.registry = new ExecutionRegistry();
        this.blockingExecutor = blockingExecutor;
        this.resultStore = resultStore;
        this.cliToolProperties = cliToolProperties;
        this.toolDescriptorRegistry = toolDescriptorRegistry;
        this.toolExecutor = toolExecutor;
    }

    private List<ToolCallback> currentToolCallbacks() {
        return toolCallbackProvider == null
                ? java.util.List.of()
                : java.util.List.of(toolCallbackProvider.getToolCallbacks());
    }

    private static ExecutorService defaultBlockingExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "agent-runtime-block");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== AgentRuntime ====================

    @Override
    public AgentExecutionResult execute(AgentTask task) {
        ExecutionContext ctx = prepare(task);
        try {
            ReActLoop.SubscribeResult sr = ctx.reactLoop.subscribe(task.getInput(), java.util.Map.of(), ctx.observers, ctx.signal);
            ctx.fullAnswer = sr.fullAnswer();
            persistTurn(task.getSessionId(), sr.turnMessages());
            postTurnMemory(task.getSessionId());
            return finalize(ctx, null);
        } catch (RuntimeException ex) {
            return finalize(ctx, ex);
        }
    }

    @Override
    public Flux<AgentEvent> stream(AgentTask task) {
        ExecutionContext ctx = prepare(task);
        return Flux.create(sink -> {
            SinkEmittingObserver bridge = new SinkEmittingObserver(sink);
            ctx.observers.add(0, bridge);

            AgentHandle handle = AgentHandle.builder()
                    .executionId(ctx.executionId)
                    .cancelAction(() -> {
                        if (!sink.isCancelled()) {
                            sink.complete();
                        }
                    })
                    .build();
            registry.register(ctx.executionId, handle);
            try {
                sink.onRequest(req -> {
                    // noop
                });
                sink.onCancel(() -> handle.cancelNow());

                try {
                    ReActLoop.SubscribeResult sr = ctx.reactLoop.subscribe(task.getInput(), java.util.Map.of(), ctx.observers, ctx.signal);
                    ctx.fullAnswer = sr.fullAnswer();
                    persistTurn(task.getSessionId(), sr.turnMessages());
                    postTurnMemory(task.getSessionId());
                    AgentExecutionResult result = finalize(ctx, null);
                    handle.markDone(result.getReason().name());
                    sink.complete();
                } catch (RuntimeException ex) {
                    AgentExecutionResult result = finalize(ctx, ex);
                    handle.markDone(result.getReason().name());
                    sink.error(new RuntimeException(result.getReason().name(), ex));
                }
            } finally {
                registry.unregister(ctx.executionId);
            }
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public AgentHandle cancel(String executionId) {
        AgentHandle handle = registry.get(executionId);
        if (handle != null) {
            handle.cancelNow();
        }
        return handle;
    }

    @Override
    public void registerObserver(ReActLoopObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    @Override
    public List<ReActLoopObserver> registeredObservers() {
        return Collections.unmodifiableList(observers);
    }

    public ExecutionRegistry registry() {
        return registry;
    }

    // ==================== 内部流程 ====================

    private void persistTurn(String sessionId, List<Message> turnMessages) {
        if (sessionStore == null || turnMessages == null || turnMessages.isEmpty()) {
            return;
        }
        try {
            sessionStore.getOrCreate(sessionId).addAll(turnMessages);
        } catch (RuntimeException ex) {
            log.warn("Failed to persist completed turn for session {}: {}", sessionId, ex.getMessage(), ex);
        }
    }

    /**
     * 每轮 mid-term 增量更新 + 标记当前 session 为 long-term 维护器的活动 session
     * （part4 §7.2 + 用户澄清 "long memo 提取异步进行"）。
     * 失败 swallow + log,不阻塞主流程。
     */
    private void postTurnMemory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (memoryTurnHook != null) {
            try {
                memoryTurnHook.onTurnFinished(sessionId);
            } catch (RuntimeException ex) {
                log.warn("memoryTurnHook failed for {}: {}", sessionId, ex.getMessage());
            }
        }
        if (longTermMaintainer != null) {
            try {
                longTermMaintainer.setActiveSessionId(sessionId);
            } catch (RuntimeException ignore) { }
        }
    }

    private ExecutionContext prepare(AgentTask task) {
        String executionId = "exec-" + UUID.randomUUID();
        AgentBudget budget;
        if (task.getBudget() != null) {
            budget = task.getBudget();
        } else if (budgetFactory != null) {
            budget = budgetFactory.defaultBudget();
        } else {
            budget = AgentBudget.defaultChat();
        }

        EventRecordingObserver recorder = new EventRecordingObserver();
        TokenBudgetObserver tokenObs = new TokenBudgetObserver(budget);
        LoopStepObserver stepObs = new LoopStepObserver(budget.getMaxSteps());
        TimeoutObserver timeoutObs = new TimeoutObserver(budget, Instant.now());

        List<ReActLoopObserver> chain = new ArrayList<>();
        chain.add(recorder);
        chain.addAll(observers);
        chain.add(tokenObs);
        chain.add(stepObs);
        chain.add(timeoutObs);
        if (autoCompressionObserver != null) {
            chain.add(autoCompressionObserver);
        }

        DefaultReActLoopSignal signal = new DefaultReActLoopSignal();
        ReActLoop loop = new ReActLoop(executionId, chatModel, task, budget, toolGateway,
                currentToolCallbacks(), sideEffectTracker, contextBuilder,
                cliToolProperties, toolDescriptorRegistry, toolExecutor, resultStore);
        AgentExecutionRecord.Builder recordBuilder = AgentExecutionRecord.builder()
                .executionId(executionId)
                .task(task)
                .startedAt(Instant.now());

        return new ExecutionContext(executionId, budget, loop, signal, chain,
                tokenObs, stepObs, timeoutObs, recorder, recordBuilder);
    }

    private AgentExecutionResult finalize(ExecutionContext ctx, RuntimeException thrown) {
        Instant finishedAt = Instant.now();
        FinishReason reason;
        if (thrown != null) {
            reason = FinishReason.ERROR;
        } else if (ctx.signal.isTerminateRequested()) {
            reason = ctx.signal.requestedReason();
        } else {
            reason = FinishReason.FINISH;
        }

        if (thrown != null) {
            LoopErrorEvent err = LoopErrorEvent.builder()
                    .executionId(ctx.executionId)
                    .at(Instant.now())
                    .stepIndex(0)
                    .agentName("runtime")
                    .message(thrown.getMessage())
                    .error(thrown)
                    .build();
            ctx.recorder.getEvents().add(err);
            dispatch(ctx.observers, o -> o.onError(err, ctx.signal));
        }

        if (FinishReason.TOKEN_LIMIT == reason) {
            TokenBudgetEvent exceeded = TokenBudgetEvent.builder()
                    .executionId(ctx.executionId)
                    .at(Instant.now())
                    .stepIndex(0)
                    .maxTokens(ctx.budget.getMaxTotalTokens())
                    .tokensUsed(ctx.tokenObs.getUsed())
                    .build();
            ctx.recorder.getEvents().add(exceeded);
            dispatch(ctx.observers, o -> o.onTokenBudgetExceeded(exceeded, ctx.signal));
        }
        if (FinishReason.CONTEXT_OVERFLOW == reason) {
            TokenBudgetEvent exceeded = TokenBudgetEvent.builder()
                    .executionId(ctx.executionId)
                    .at(Instant.now())
                    .stepIndex(0)
                    .maxTokens(ctx.tokenObs.getEffectivePerCallPromptBudget())
                    .tokensUsed(ctx.tokenObs.getLastPerCallPrompt())
                    .build();
            ctx.recorder.getEvents().add(exceeded);
            dispatch(ctx.observers, o -> o.onTokenBudgetExceeded(exceeded, ctx.signal));
        }
        if (FinishReason.LOOP_LIMIT == reason) {
            LoopBudgetEvent exceeded = LoopBudgetEvent.builder()
                    .executionId(ctx.executionId)
                    .at(Instant.now())
                    .stepIndex(0)
                    .maxSteps(ctx.budget.getMaxSteps())
                    .stepsTaken(ctx.stepObs.getStepsTaken())
                    .build();
            ctx.recorder.getEvents().add(exceeded);
            dispatch(ctx.observers, o -> o.onLoopBudgetExceeded(exceeded, ctx.signal));
        }

        String finalAnswer = collectFinalAnswer(ctx);
        if (ctx.fullAnswer != null && !ctx.fullAnswer.isEmpty()) {
            finalAnswer = ctx.fullAnswer;
        }
        if (reason != FinishReason.FINISH && (finalAnswer == null || finalAnswer.isEmpty())) {
            finalAnswer = defaultFallback(reason);
        }

        FinishEvent finish = FinishEvent.builder()
                .executionId(ctx.executionId)
                .at(Instant.now())
                .stepIndex(ctx.recorder.getEvents().size())
                .reason(reason)
                .finalAnswer(finalAnswer)
                .totalTokensUsed(ctx.tokenObs.getUsed())
                .build();
        ctx.recorder.getEvents().add(finish);
        dispatch(ctx.observers, o -> o.onFinish(finish, ctx.signal));

        AgentExecutionRecord record = ctx.recordBuilder
                .finishedAt(finishedAt)
                .events(new ArrayList<>(ctx.recorder.getEvents()))
                .build();

        return AgentExecutionResult.builder()
                .reason(reason)
                .finalAnswer(finalAnswer)
                .executionId(ctx.executionId)
                .eventLog(new ArrayList<>(ctx.recorder.getEvents()))
                .totalTokensUsed(ctx.tokenObs.getUsed())
                .totalSteps(ctx.stepObs.getStepsTaken())
                .record(record)
                .build();
    }

    private String collectFinalAnswer(ExecutionContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : ctx.recorder.getEvents()) {
            if (e instanceof org.example.agent.core.event.ThoughtEvent te) {
                if (te.getThoughtText() != null) {
                    sb.append(te.getThoughtText());
                }
            }
        }
        return sb.toString();
    }

    private static String defaultFallback(FinishReason reason) {
        return switch (reason) {
            case TOKEN_LIMIT -> "（已达到单次执行的 token 预算上限，结果被截断）";
            case LOOP_LIMIT -> "（已达到单次执行的步数上限，已返回当前观察到的最佳结果）";
            case WALLCLOCK_LIMIT -> "（已达到单次执行的 wallclock 上限，执行已终止）";
            case CONTEXT_OVERFLOW -> "（单次调用的 prompt 已超出上下文窗口有效空间，压缩后仍超，已终止）";
            case CANCELLED -> "（执行已被取消）";
            case ERROR -> "（执行出错）";
            case FINISH -> "";
        };
    }

    private static void dispatch(List<ReActLoopObserver> chain,
                                 java.util.function.Consumer<ReActLoopObserver> invoker) {
        for (ReActLoopObserver o : chain) {
            try {
                invoker.accept(o);
            } catch (Exception ex) {
                log.warn("observer dispatch failed: {}", ex.getMessage(), ex);
            }
        }
    }

    private static final class ExecutionContext {
        final String executionId;
        final AgentBudget budget;
        final ReActLoop reactLoop;
        final DefaultReActLoopSignal signal;
        final List<ReActLoopObserver> observers;
        final TokenBudgetObserver tokenObs;
        final LoopStepObserver stepObs;
        final TimeoutObserver timeoutObs;
        final EventRecordingObserver recorder;
        final AgentExecutionRecord.Builder recordBuilder;
        volatile String fullAnswer;

        ExecutionContext(String executionId,
                         AgentBudget budget,
                         ReActLoop reactLoop,
                         DefaultReActLoopSignal signal,
                         List<ReActLoopObserver> observers,
                         TokenBudgetObserver tokenObs,
                         LoopStepObserver stepObs,
                         TimeoutObserver timeoutObs,
                         EventRecordingObserver recorder,
                         AgentExecutionRecord.Builder recordBuilder) {
            this.executionId = executionId;
            this.budget = budget;
            this.reactLoop = reactLoop;
            this.signal = signal;
            this.observers = observers;
            this.tokenObs = tokenObs;
            this.stepObs = stepObs;
            this.timeoutObs = timeoutObs;
            this.recorder = recorder;
            this.recordBuilder = recordBuilder;
        }
    }
}

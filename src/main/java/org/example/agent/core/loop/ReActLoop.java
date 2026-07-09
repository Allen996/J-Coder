package org.example.agent.core.loop;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.kind.StepKind;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.record.StepRecord;
import org.example.agent.core.signal.ReActLoopSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 关键设计：
 *  - 内部持有 Spring AI Alibaba 的 ReactAgent（4.1 阶段对底层的唯一接触点）
 *  - 暴露一个 {@link #subscribe(String, Map, List, ReActLoopSignal)} 方法，
 *    让 AgentRuntimeImpl 把上层的「观察者 + 信号面」喂进来，由本类负责把
 *    Flux<NodeOutput> 中的 OutputType 翻译成 AgentEvent 并触发观察者
 *  - 同时增量记录 StepRecord，方便 AgentRuntimeImpl 落到 AgentExecutionRecord
 *  - 线程模型：所有 event 派发与 StepRecord 累积都在订阅线程（Flux 的线程）
 *    同步进行，不引入额外线程切换
 *
 * ReActLoop 本身不强制 budget（不知道 maxSteps / maxTokens），它把这件事
 * 委托给 observers（TokenBudgetObserver / LoopStepObserver / TimeoutObserver）。
 */
public class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final String executionId;
    private final ReactAgent reactAgent;
    private final AgentBudget budget;

    public ReActLoop(String executionId, ReactAgent reactAgent, AgentBudget budget) {
        this.executionId = executionId;
        this.reactAgent = reactAgent;
        this.budget = budget;
    }

    public String getExecutionId() { return executionId; }
    public ReactAgent getReactAgent() { return reactAgent; }
    public AgentBudget getBudget() { return budget; }

    /**
     * 订阅一次执行。返回本次执行产生的所有 StepRecord（按时间顺序）。
     * 调用方应在 subscribe 之前准备好 observers + signal。
     *
     * @param input          用户输入文本
     * @param toolArgsMap    本次执行用到的 tool 参数表（key=toolName, value=空 args 占位）；
     *                       当前 Spring AI Alibaba 不对外暴露 per-step args，
     *                       故本参数保留给未来 hook/observer 扩展点
     * @param observers      事件订阅者列表
     * @param signal         协作式控制面（observers 通过它发起强制终止）
     * @return 本次执行追加的 StepRecord 列表
     */
    public List<StepRecord> subscribe(String input,
                                      Map<String, Object> toolArgsMap,
                                      List<ReActLoopObserver> observers,
                                      ReActLoopSignal signal) {
        AtomicInteger stepCounter = new AtomicInteger(0);
        AtomicLong lastStepStart = new AtomicLong(System.currentTimeMillis());
        List<StepRecord> records = Collections.synchronizedList(new ArrayList<>());
        StringBuilder fullAnswer = new StringBuilder();

        List<NodeOutput> outputs = invokeInternal(input);
        for (NodeOutput output : outputs) {
            if (signal.isTerminateRequested()) {
                break;
            }
            dispatch(output, observers, signal, stepCounter, lastStepStart, records, fullAnswer);
        }

        return records;
    }

    /** 当前累积的 fullAnswer，用于 AgentRuntime 上报最终答案。 */
    public static String extractFinalAnswer(StringBuilder builder) {
        return builder.toString();
    }

    // ============== 内部 ==============

    private List<NodeOutput> invokeInternal(String input) {
        try {
            // stream 返回 Flux<NodeOutput>，为了简化线程模型与测试，
            // 4.1 阶段使用 buffer 收集一次性处理。生产路径可改成 subscribe。
            return reactAgent.stream(input).collectList().block();
        } catch (GraphRunnerException e) {
            throw new IllegalStateException("ReactAgent.stream failed: " + e.getMessage(), e);
        }
    }

    private void dispatch(NodeOutput output,
                          List<ReActLoopObserver> observers,
                          ReActLoopSignal signal,
                          AtomicInteger stepCounter,
                          AtomicLong lastStepStart,
                          List<StepRecord> records,
                          StringBuilder fullAnswer) {
        Instant at = Instant.now();
        try {
            if (output instanceof StreamingOutput streaming) {
                OutputType type = streaming.getOutputType();
                if (type == OutputType.AGENT_MODEL_FINISHED) {
                    int step = stepCounter.incrementAndGet();
                    String text = streaming.message() == null ? "" : streaming.message().getText();
                    if (text != null) {
                        fullAnswer.append(text);
                    }
                    long prompt = 0L;
                    long completion = 0L;
                    if (streaming.message() instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
                        Map<String, Object> meta = am.getMetadata();
                        if (meta != null) {
                            Object usage = meta.get("usage");
                            if (usage instanceof org.springframework.ai.chat.metadata.Usage u) {
                                prompt = u.getPromptTokens() == null ? 0L : u.getPromptTokens();
                                completion = u.getCompletionTokens() == null ? 0L : u.getCompletionTokens();
                            }
                        }
                    }
                    ThoughtEvent ev = ThoughtEvent.builder()
                            .executionId(executionId)
                            .at(at)
                            .stepIndex(step)
                            .agentName(reactAgent.name())
                            .thoughtText(text)
                            .promptTokens(prompt)
                            .completionTokens(completion)
                            .build();
                    safeInvokeThought(observers, ev, signal);
                    records.add(StepRecord.builder()
                            .stepIndex(step)
                            .kind(StepKind.THOUGHT)
                            .at(at)
                            .agentName(reactAgent.name())
                            .thoughtSummary(text)
                            .latencyMs(System.currentTimeMillis() - lastStepStart.get())
                            .tokensConsumed(prompt + completion)
                            .build());
                    lastStepStart.set(System.currentTimeMillis());
                } else if (type == OutputType.AGENT_MODEL_STREAMING) {
                    // 流式增量 chunk 把文本持续追加 fullAnswer，不开新 step
                    String chunk = streaming.chunk();
                    if (chunk != null && !chunk.isEmpty()) {
                        fullAnswer.append(chunk);
                    }
                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                    int step = stepCounter.get();
                    String toolName = streaming.chunk();
                    if (toolName == null || toolName.isBlank()) {
                        toolName = streaming.node();
                    }
                    ActionInvokedEvent inv = ActionInvokedEvent.builder()
                            .executionId(executionId)
                            .at(at)
                            .stepIndex(step)
                            .agentName(reactAgent.name())
                            .toolName(toolName)
                            .build();
                    safeInvokeActionInvoked(observers, inv, signal);
                    ObservationEvent obs = ObservationEvent.builder()
                            .executionId(executionId)
                            .at(at)
                            .stepIndex(step)
                            .agentName(reactAgent.name())
                            .toolName(toolName)
                            .status(ObservationEvent.Status.OK)
                            .observationText(streaming.message() == null ? "" : streaming.message().getText())
                            .latencyMs(System.currentTimeMillis() - lastStepStart.get())
                            .build();
                    safeInvokeObservation(observers, obs, signal);
                    records.add(StepRecord.builder()
                            .stepIndex(step)
                            .kind(StepKind.ACTION)
                            .at(at)
                            .agentName(reactAgent.name())
                            .toolName(toolName)
                            .toolArgs(new HashMap<>())
                            .build());
                    records.add(StepRecord.builder()
                            .stepIndex(step)
                            .kind(StepKind.OBSERVATION)
                            .at(at)
                            .agentName(reactAgent.name())
                            .toolName(toolName)
                            .observationText(obs.getObservationText())
                            .latencyMs(obs.getLatencyMs())
                            .build());
                    lastStepStart.set(System.currentTimeMillis());
                }
            }
        } catch (Exception ex) {
            log.error("executionId={} dispatch failed", executionId, ex);
            LoopErrorEvent err = LoopErrorEvent.builder()
                    .executionId(executionId)
                    .at(at)
                    .stepIndex(stepCounter.get())
                    .agentName(reactAgent.name())
                    .message(ex.getMessage())
                    .error(ex)
                    .build();
            safeInvokeError(observers, err, signal);
        }
    }

    private void safeInvokeThought(List<ReActLoopObserver> os, ThoughtEvent e, ReActLoopSignal s) {
        for (ReActLoopObserver o : os) { try { o.onThought(e, s); } catch (Exception ex) { log.warn("observer onThought failed", ex); } }
    }
    private void safeInvokeActionInvoked(List<ReActLoopObserver> os, ActionInvokedEvent e, ReActLoopSignal s) {
        // 先 preCheck
        ActionPreCheckEvent pre = ActionPreCheckEvent.builder()
                .executionId(e.getExecutionId())
                .at(e.getAt())
                .stepIndex(e.getStepIndex())
                .agentName(e.getAgentName())
                .toolName(e.getToolName())
                .args(Map.of())
                .build();
        for (ReActLoopObserver o : os) { try { o.onActionPreCheck(pre, s); } catch (Exception ex) { log.warn("observer onActionPreCheck failed", ex); } }
        for (ReActLoopObserver o : os) { try { o.onActionInvoked(e, s); } catch (Exception ex) { log.warn("observer onActionInvoked failed", ex); } }
    }
    private void safeInvokeObservation(List<ReActLoopObserver> os, ObservationEvent e, ReActLoopSignal s) {
        for (ReActLoopObserver o : os) { try { o.onObservation(e, s); } catch (Exception ex) { log.warn("observer onObservation failed", ex); } }
    }
    private void safeInvokeError(List<ReActLoopObserver> os, LoopErrorEvent e, ReActLoopSignal s) {
        for (ReActLoopObserver o : os) { try { o.onError(e, s); } catch (Exception ex) { log.warn("observer onError failed", ex); } }
    }
}

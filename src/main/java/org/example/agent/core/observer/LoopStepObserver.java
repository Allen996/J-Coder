package org.example.agent.core.observer;

import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.reason.FinishReason;
import org.example.agent.core.signal.ReActLoopSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次任务最多 N 步（一次 thought + 一次 action 计 1 步），到了强 FINISH。
 *
 * 步骤计数规则：
 *  - ThoughtEvent + 一次 ActionPreCheck/Invoked/Observation 视为 1 步
 *  - 模型直接给出 final answer（无 tool call）也视为 1 步
 *  - 任何失败 / 取消步骤不计入 stepsTaken（在 AgentRuntimeImpl 中分别处理）
 *
 * AgentRuntimeImpl 在 dispatch 完 onObservation 后做检查；超过 maxSteps 则
 * requestTerminate(LOOP_LIMIT) 并把当前 observation 摘要作为 finalAnswer 兜底。
 */
public class LoopStepObserver implements ReActLoopObserver {

    private static final Logger log = LoggerFactory.getLogger(LoopStepObserver.class);

    private final int maxSteps;
    private final AtomicInteger stepsTaken = new AtomicInteger(0);
    private volatile int stepsWhenBudgetHit;

    public LoopStepObserver(int maxSteps) {
        this.maxSteps = Math.max(1, maxSteps);
    }

    public int getMaxSteps() { return maxSteps; }
    public int getStepsTaken() { return stepsTaken.get(); }
    public int getStepsWhenBudgetHit() { return stepsWhenBudgetHit; }

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        // Thought 不立刻 +1；AgentRuntimeImpl 会在一个完整 step 结束后回调一次
        // onObservation 时再 +1，方便把「思考-行动-观察」算一个完整步。
    }

    @Override
    public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        int taken = stepsTaken.incrementAndGet();
        if (taken > maxSteps && !signal.isTerminateRequested()) {
            stepsWhenBudgetHit = taken;
            log.info("executionId={} loop step budget exceeded: taken={}, max={}",
                    event.getExecutionId(), taken, maxSteps);
            signal.requestTerminate(FinishReason.LOOP_LIMIT);
        }
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) {
        log.warn("executionId={} loop budget snapshot: taken={}, max={}",
                event.getExecutionId(), event.getStepsTaken(), event.getMaxSteps());
    }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) { /* noop */ }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) { /* noop */ }
}

package org.example.agent.core.task.orchestrator;

import lombok.Getter;
import org.example.agent.core.event.ActionInvokedEvent;
import org.example.agent.core.event.ActionPreCheckEvent;
import org.example.agent.core.event.FinishEvent;
import org.example.agent.core.event.LoopBudgetEvent;
import org.example.agent.core.event.LoopErrorEvent;
import org.example.agent.core.event.ObservationEvent;
import org.example.agent.core.event.RollbackEvent;
import org.example.agent.core.event.ThoughtEvent;
import org.example.agent.core.event.TokenBudgetEvent;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.signal.ReActLoopSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单 SubTask 内的 ReAct 循环观察者（part5 §8.8）。
 *
 * <p>只关心"complete_subtask / fail_subtask / skip_subtask / save_checkpoint 是否被调用"
 * —— 它们是 SubTask 终结的显式信号。其它工具调用忽略。
 *
 * <p>每个 SubTask 启动时由 orchestrator 新建一个实例，register 到 AgentRuntime，
 * run 结束后从 runtime 注销。
 */
@Getter
public class TaskLoopObserver implements ReActLoopObserver {

    private static final Logger log = LoggerFactory.getLogger(TaskLoopObserver.class);

    private final String taskId;

    private volatile boolean completeSubtaskCalled = false;
    private volatile String completeNote = "";
    private volatile boolean failSubtaskCalled = false;
    private volatile String failReason = "";
    private volatile boolean skipSubtaskCalled = false;
    private volatile String skipReason = "";
    private final Set<String> touchedFiles = new HashSet<>();
    private volatile boolean budgetExhausted = false;
    private final AtomicInteger stepsTaken = new AtomicInteger(0);

    public TaskLoopObserver(String taskId) {
        this.taskId = taskId;
    }

    public SubTaskOutcome buildOutcome() {
        int steps = stepsTaken.get();
        if (completeSubtaskCalled) {
            return SubTaskOutcome.builder()
                    .kind(SubTaskOutcome.Kind.COMPLETED_EXPLICIT)
                    .note(completeNote)
                    .attempts(1)
                    .stepsTaken(steps)
                    .build();
        }
        if (failSubtaskCalled) {
            return SubTaskOutcome.builder()
                    .kind(SubTaskOutcome.Kind.FAILED_EXPLICIT)
                    .failureReason(failReason == null || failReason.isBlank() ? "explicit fail" : failReason)
                    .attempts(1)
                    .stepsTaken(steps)
                    .build();
        }
        if (skipSubtaskCalled) {
            return SubTaskOutcome.builder()
                    .kind(SubTaskOutcome.Kind.SKIPPED_EXPLICIT)
                    .failureReason(skipReason == null || skipReason.isBlank() ? "explicit skip" : skipReason)
                    .attempts(1)
                    .stepsTaken(steps)
                    .build();
        }
        if (budgetExhausted) {
            return SubTaskOutcome.builder()
                    .kind(SubTaskOutcome.Kind.BUDGET_EXHAUSTED)
                    .failureReason("step budget exhausted")
                    .attempts(1)
                    .stepsTaken(steps)
                    .build();
        }
        return SubTaskOutcome.builder()
                .kind(SubTaskOutcome.Kind.ERROR)
                .failureReason("loop terminated without explicit completion")
                .attempts(1)
                .stepsTaken(steps)
                .build();
    }

    public Set<String> touchedFiles() {
        return new HashSet<>(touchedFiles);
    }

    public void markCompleteSubtask(String note) {
        this.completeSubtaskCalled = true;
        if (note != null) this.completeNote = note;
    }

    public void markFailSubtask(String reason) {
        this.failSubtaskCalled = true;
        if (reason != null) this.failReason = reason;
    }

    public void markSkipSubtask(String reason) {
        this.skipSubtaskCalled = true;
        if (reason != null) this.skipReason = reason;
    }

    public void markBudgetExhausted() {
        this.budgetExhausted = true;
    }

    // ============ ReActLoopObserver ============

    @Override
    public void onThought(ThoughtEvent event, ReActLoopSignal signal) {
        // noop —— 不计 step
    }

    @Override
    public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) {
        // 文件类工具调用前收集 touchedFiles
        String tool = event.getToolName();
        if (tool != null && (tool.equals("write_file") || tool.equals("edit_file"))) {
            Object path = event.getArgs() == null ? null : event.getArgs().get("path");
            if (path != null) touchedFiles.add(String.valueOf(path));
        }
    }

    @Override
    public void onActionInvoked(ActionInvokedEvent event, ReActLoopSignal signal) {
        // noop
    }

    @Override
    public void onObservation(ObservationEvent event, ReActLoopSignal signal) {
        stepsTaken.incrementAndGet();
        // 注意: complete_subtask 等工具的真正生效由 orchestrator 在工具调用后处理；
        // 这里只暴露 hook（TaskPlanTools 调 markCompleteSubtask 等）。
    }

    @Override
    public void onRollback(RollbackEvent event, ReActLoopSignal signal) {
        // noop
    }

    @Override
    public void onLoopBudgetExceeded(LoopBudgetEvent event, ReActLoopSignal signal) {
        markBudgetExhausted();
        log.info("TaskLoopObserver[{}] loop budget exhausted: taken={}/{}",
                taskId, event.getStepsTaken(), event.getMaxSteps());
    }

    @Override
    public void onTokenBudgetExceeded(TokenBudgetEvent event, ReActLoopSignal signal) {
        // token 超限也算 budget exhausted
        markBudgetExhausted();
    }

    @Override
    public void onPromptBuilt(java.util.List<org.springframework.ai.chat.messages.Message> messages, int stepIndex) {
        // noop
    }

    @Override
    public void onFinish(FinishEvent event, ReActLoopSignal signal) {
        // noop —— orchestrator 在 run() 返回后读 buildOutcome()
    }

    @Override
    public void onError(LoopErrorEvent event, ReActLoopSignal signal) {
        // 错误也视作 budget exhausted (没法再继续了)
        markBudgetExhausted();
        log.warn("TaskLoopObserver[{}] onError: {}", taskId, event.getMessage());
    }
}
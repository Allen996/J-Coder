package org.example.cli.renderer;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.event.PlanFinishedEvent;
import org.example.agent.core.task.event.SubTaskCompletedEvent;
import org.example.agent.core.task.event.SubTaskFailedEvent;
import org.example.agent.core.task.event.SubTaskStartedEvent;
import org.example.agent.core.task.event.TaskEventPublisher;
import org.example.agent.core.task.event.TaskObserver;
import org.example.agent.core.task.event.TaskPlanCreatedEvent;
import org.example.agent.core.task.event.VerifyFailedEvent;
import org.example.agent.core.task.event.VerifyPassedEvent;
import org.example.agent.core.task.event.VerifyStartedEvent;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把 TaskEvent 流渲染为状态行（part5 §8.8 "CLI 渲染"）。
 *
 * <p>与 CliRenderer 平行 —— TaskEvent 不混入 AgentEvent 流，独立打印。
 * REPL 主线程调 {@link #drainTo(PrintWriter, long)} 把渲染好的行 flush 到 terminal。
 *
 * <p>线程模型：TaskEventPublisher 同步派发 observer → 推到 queue → REPL 主线程消费。
 */
@Slf4j
@Component
public class TaskProgressRenderer implements TaskObserver {

    private static final int QUEUE_CAPACITY = 256;

    private final BlockingQueue<String> renderQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final TaskEventPublisher publisher;
    private final TaskOrchestrator orchestrator;

    public TaskProgressRenderer(TaskEventPublisher publisher,
                                @Lazy TaskOrchestrator orchestrator) {
        this.publisher = publisher;
        this.orchestrator = orchestrator;
    }

    @PostConstruct
    public void register() {
        publisher.register(this);
        log.debug("TaskProgressRenderer registered");
    }

    public int drainTo(PrintWriter out, long timeoutMs) {
        if (out == null) return 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        int n = 0;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            String line;
            try {
                line = renderQueue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            if (line == null) break;
            out.println(line);
            n++;
        }
        out.flush();
        return n;
    }

    private void enqueue(String line) {
        try {
            if (!renderQueue.offer(line, 100, TimeUnit.MILLISECONDS)) {
                log.warn("task renderQueue full; dropping: {}", abbreviate(line, 80));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Override
    public void onTaskPlanCreated(TaskPlanCreatedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.CYAN_BOLD,
                String.format("[plan] %s  goal: %s  subtasks=%d",
                        event.getPlanId(), event.getGoal(), event.getSubtaskCount())));
    }

    @Override
    public void onSubTaskStarted(SubTaskStartedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.YELLOW,
                String.format("[plan] %s  → %s  %s", event.getPlanId(), event.getTaskId(), event.getTitle())));
    }

    @Override
    public void onSubTaskCompleted(SubTaskCompletedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.GREEN,
                String.format("[plan] %s  ✓ %s (attempt %d)", event.getPlanId(), event.getTaskId(), event.getAttempts())));
    }

    @Override
    public void onSubTaskFailed(SubTaskFailedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.RED,
                String.format("[plan] %s  ✗ %s  %s", event.getPlanId(), event.getTaskId(), event.getReason())));
    }

    @Override
    public void onVerifyStarted(VerifyStartedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.CYAN,
                String.format("[plan] %s  verify %s running: %s",
                        event.getPlanId(), event.getTaskId(), event.getCommand())));
    }

    @Override
    public void onVerifyPassed(VerifyPassedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                String.format("[plan] %s  ✓✓ verify %s PASSED (exit %d)",
                        event.getPlanId(), event.getTaskId(), event.getExitCode())));
    }

    @Override
    public void onVerifyFailed(VerifyFailedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.YELLOW_BOLD,
                String.format("[plan] %s  verify %s FAILED (exit %d, attempt %d) log=%s",
                        event.getPlanId(), event.getTaskId(), event.getExitCode(), event.getAttempt(), event.getLogPath())));
    }

    @Override
    public void onPlanFinished(PlanFinishedEvent event) {
        enqueue(AnsiStyle.wrap(AnsiStyle.MAGENTA_BOLD,
                String.format("[plan] %s  %s  (%d subtasks, %d steps)",
                        event.getPlanId(), event.getOutcome(), event.getTotalSubtasks(), event.getTotalSteps())));
    }

    public String currentProgressLine() {
        return orchestrator.activePlan()
                .map(plan -> {
                    int total = plan.getSubtaskIds().size();
                    int done = (int) orchestrator.snapshotSubtasks().values().stream()
                            .filter(s -> s.getStatus() == org.example.agent.core.task.SubTaskStatus.VERIFIED
                                    || s.getStatus() == org.example.agent.core.task.SubTaskStatus.COMPLETED)
                            .count();
                    return String.format("[plan] %s  %d/%d  %s",
                            plan.getPlanId(), done, total,
                            plan.getCurrentTaskId() == null ? "(idle)" : "→ " + plan.getCurrentTaskId());
                })
                .orElse(null);
    }
}
package org.example.cli.command.impl;

import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.event.TaskEventPublisher;
import org.example.agent.core.task.event.VerifyStartedEvent;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.core.task.orchestrator.TaskOrchestratorConfig;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.example.agent.core.task.verify.VerifyCommandTemplate;
import org.example.agent.core.task.verify.VerifyResult;
import org.example.agent.core.task.verify.VerifyRunner;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * /verify —— 手动触发当前 plan 的 VERIFY 子任务（part5 §8.8）。
 *
 * <p>默认自动跑（orchestrator.runActivePlan 已经把 VERIFY 串入）。
 * 本命令的用途是「跳过 orchestrator 直接复跑 VERIFY」，便于用户主动验证某一次修复后是否过线。
 *
 * <p>它不会自动重跑 LLM 步骤，只重新执行 VerifyRunner —— 假设当前 SubTask 已经是 VERIFY。
 * 如果当前 plan 中没有 VERIFY 状态合适的 SubTask，提示用户。
 */
@Component
public class VerifyCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;
    private final TaskPlanRepository repository;
    private final VerifyRunner verifyRunner;
    private final TaskEventPublisher publisher;
    private final TaskOrchestratorConfig config;

    public VerifyCommand(TaskOrchestrator orchestrator,
                         TaskPlanRepository repository,
                         VerifyRunner verifyRunner,
                         TaskEventPublisher publisher,
                         TaskOrchestratorConfig config) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.verifyRunner = verifyRunner;
        this.publisher = publisher;
        this.config = config;
    }

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "manually re-run VERIFY on the active plan (Part 5 §8.8)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        Optional<TaskPlan> planOpt = orchestrator.activePlan();
        if (planOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no active plan)"));
            ctx.out().flush();
            return 0;
        }
        TaskPlan plan = planOpt.get();
        Optional<SubTask> verifySubOpt = findVerifySubTask(plan);
        if (verifySubOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                    "(no VERIFY SubTask in this plan — run orchestrator-driven plan first)"));
            ctx.out().flush();
            return 0;
        }
        SubTask sub = verifySubOpt.get();
        VerifyCommandTemplate template = verifyRunner.detect(config.getProjectRoot());
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.CYAN_BOLD,
                "[verify] " + template.getDescription() + " → " + template.renderCommand()));
        ctx.out().flush();

        publisher.publish(new VerifyStartedEvent(plan.getPlanId(), sub.getTaskId(),
                Instant.now(), template.renderCommand()));
        VerifyResult result = verifyRunner.run(config.getProjectRoot(), template,
                repository.verifyLogPath(plan.getPlanId()));
        ctx.out().println(AnsiStyle.wrap(result.passed() ? AnsiStyle.GREEN_BOLD : AnsiStyle.RED_BOLD,
                String.format("[verify] exit=%d  duration=%dms", result.getExitCode(), result.getDurationMs())));
        if (!result.getLogTail().isBlank()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM, "--- tail ---"));
            ctx.out().println(result.getLogTail());
        }
        ctx.out().flush();
        return result.passed() ? 0 : 3;
    }

    private Optional<SubTask> findVerifySubTask(TaskPlan plan) {
        for (String id : plan.getSubtaskIds()) {
            SubTask sub = orchestrator.findSubTaskForRender(id).orElse(null);
            if (sub == null) continue;
            if (sub.getType() == SubTaskType.VERIFY
                    && (sub.getStatus() == SubTaskStatus.PENDING
                        || sub.getStatus() == SubTaskStatus.COMPLETED
                        || sub.getStatus() == SubTaskStatus.FAILED)) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }
}
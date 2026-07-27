package org.example.cli.command.impl;

import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * /resume —— 恢复磁盘上的 ACTIVE TaskPlan（part5 §8.9 "跨 session: /resume <planId>"）。
 *
 * <p>两个用法：
 * <ul>
 *   <li>无参数：列出 status=ACTIVE 的可恢复 plan，提示用户输入 {@code /resume <planId>}</li>
 *   <li>有参数(planId)：从磁盘 load plan 与全部 SubTask，调用 {@link TaskOrchestrator#adoptPlan}，
 *       再调 {@link TaskOrchestrator#resumeActivePlan()} 清 paused=true，提示下一步输入即触发续跑</li>
 * </ul>
 *
 * <p>不接 SQLite session DB（属于 Part 4 范围）。这里只解决 part5 任务系统的最小恢复需求。
 */
@Component
public class ResumeCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;
    private final TaskPlanRepository repository;

    public ResumeCommand(TaskOrchestrator orchestrator, TaskPlanRepository repository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "resume a TaskPlan by id (Part 5 §8.9); no args → list resumable plans";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String id = args == null ? "" : args.trim();
        if (id.isEmpty()) {
            return listResumable(ctx);
        }
        return adoptPlan(id, ctx);
    }

    private int listResumable(CliContext ctx) {
        List<TaskPlan> active = repository.listAllPlans().stream()
                .filter(p -> p.getStatus() == TaskPlanStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no resumable plan — no ACTIVE plan on disk)"));
            ctx.out().flush();
            return 0;
        }
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.CYAN_BOLD,
                active.size() + " resumable plan(s):"));
        for (TaskPlan p : active) {
            String paused = p.isPaused() ? " [paused]" : "";
            String current = p.getCurrentTaskId() == null ? "" : "  current=" + p.getCurrentTaskId();
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.CYAN,
                    "  · " + p.getPlanId() + paused + "  goal=" + truncate(p.getGoal(), 60) + current));
        }
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                "usage: /resume <planId>"));
        ctx.out().flush();
        return 0;
    }

    private int adoptPlan(String planId, CliContext ctx) {
        Optional<TaskPlan> planOpt = repository.loadPlan(planId);
        if (planOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.RED, "no such plan: " + planId));
            ctx.out().flush();
            return 1;
        }
        TaskPlan plan = planOpt.get();
        if (plan.getStatus() != TaskPlanStatus.ACTIVE) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                    "plan " + planId + " is " + plan.getStatus() + " — only ACTIVE plans can be resumed"));
            ctx.out().flush();
            return 2;
        }
        List<SubTask> subs = repository.loadAllSubTasks(planId);
        orchestrator.adoptPlan(plan, subs);
        // 清 paused=true,确保 orchestrator.runActivePlan() 不会被立刻 break
        orchestrator.resumeActivePlan();

        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                "adopted plan " + planId + " with " + subs.size() + " subtask(s)"));
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                "next agent turn will resume execution via orchestrator.runActivePlan()"));
        ctx.out().flush();
        return 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
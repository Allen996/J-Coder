package org.example.cli.command.impl;

import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

/**
 * /plan-resume —— 把当前 active plan 的 paused 标志清掉，让下一轮 ReAct 收尾后
 * 重新触发 {@code orchestrator.runActivePlan()} 续跑（part5 §8.9 用户中途插入最小实现）。
 *
 * <p>与 {@code /resume <planId>} 区分：
 * <ul>
 *   <li>{@code /resume <planId>} —— 从磁盘 adopt 一个已经持久化的 plan（跨 session / 进程重启）</li>
 *   <li>{@code /plan-resume} —— 续跑当前进程内已经 active 但被 /pause 挂起的 plan</li>
 * </ul>
 */
@Component
public class PlanResumeCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;

    public PlanResumeCommand(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "plan-resume";
    }

    @Override
    public String description() {
        return "resume a paused active TaskPlan in current session (Part 5 §8.9)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        var planOpt = orchestrator.activePlan();
        if (planOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no active plan)"));
            ctx.out().flush();
            return 0;
        }
        var plan = planOpt.get();
        if (plan.getStatus() != TaskPlanStatus.ACTIVE) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                    "plan " + plan.getPlanId() + " is " + plan.getStatus() + " — cannot resume"));
            ctx.out().flush();
            return 2;
        }
        orchestrator.resumeActivePlan();
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                "plan " + plan.getPlanId() + " resumed"));
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                "next agent turn will trigger orchestrator.runActivePlan()"));
        ctx.out().flush();
        return 0;
    }
}
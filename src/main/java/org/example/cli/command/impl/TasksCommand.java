package org.example.cli.command.impl;

import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

/**
 * /tasks —— 列出当前 active plan 的所有 SubTask 与状态（part5 §8.8 CLI 渲染）。
 */
@Component
public class TasksCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;

    public TasksCommand(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "tasks";
    }

    @Override
    public String description() {
        return "list SubTasks of the active plan (Part 5 §8.8)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        var planOpt = orchestrator.activePlan();
        if (planOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                    "(no active plan — create one via /create_plan tool call inside an agent turn)"));
            ctx.out().flush();
            return 0;
        }
        String text = orchestrator.queryPlanAsText(planOpt.get().getPlanId());
        ctx.out().print(text);
        ctx.out().flush();
        return 0;
    }
}
package org.example.cli.command.impl;

import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

/**
 * /pause —— 把当前 active plan 置 paused=true（part5 §8.9 用户中途插入最小实现）。
 *
 * <p>不影响已经在跑的 SubTask，但下一轮 {@code orchestrator.runActivePlan()} 会在
 * {@code plan.isPaused()} 检查处 break —— 配合 {@code /plan-resume} 命令使用。
 *
 * <p>注意：完整的 §8.9 设计包含异步 stdin 监听（Esc 键中断），本次最小实现不覆盖；
 * 用户需要主动调用本命令触发暂停。
 */
@Component
public class PauseCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;

    public PauseCommand(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "pause";
    }

    @Override
    public String description() {
        return "pause the active TaskPlan at the next checkpoint (Part 5 §8.9)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        if (orchestrator.activeGraph().isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no active plan)"));
            ctx.out().flush();
            return 0;
        }
        orchestrator.pauseActivePlan();
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GREEN_BOLD,
                "plan " + orchestrator.activeGraph().get().getPlanId() + " paused"));
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                "use /tasks to inspect"));
        ctx.out().flush();
        return 0;
    }
}
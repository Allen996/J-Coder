package org.example.cli.command.impl;

import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * /task &lt;taskId&gt; —— 显示单个 SubTask 详情（part5 §8.8 CLI 渲染）。
 */
@Component
public class TaskCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;

    public TaskCommand(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "show one SubTask detail by id (Part 5 §8.8)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String taskId = args == null ? "" : args.trim();
        if (taskId.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.RED, "usage: /task <taskId>"));
            ctx.out().flush();
            return 2;
        }
        var planOpt = orchestrator.activePlan();
        if (planOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no active plan)"));
            ctx.out().flush();
            return 0;
        }
        Optional<SubTask> subOpt = orchestrator.findSubTaskForRender(taskId);
        if (subOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.RED, "subtask not found: " + taskId));
            ctx.out().flush();
            return 2;
        }
        SubTask sub = subOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(sub.getTaskId()).append("] ")
                .append(sub.getStatus()).append(" · ").append(sub.getType()).append('\n');
        sb.append("title: ").append(sub.getTitle()).append('\n');
        if (sub.getDescription() != null && !sub.getDescription().isBlank()) {
            sb.append("description: ").append(sub.getDescription()).append('\n');
        }
        sb.append("dependsOn: ").append(sub.getDependsOn()).append('\n');
        sb.append("attempts: ").append(sub.getAttempts()).append('\n');
        sb.append("artifacts: ").append(sub.getArtifacts()).append('\n');
        if (sub.getDone() != null && !sub.getDone().isBlank()) {
            sb.append("\n-- done --\n").append(sub.getDone()).append('\n');
        }
        if (sub.getCurrentAction() != null && !sub.getCurrentAction().isBlank()) {
            sb.append("\n-- in progress --\n").append(sub.getCurrentAction()).append('\n');
        }
        if (sub.getNextStep() != null && !sub.getNextStep().isBlank()) {
            sb.append("\n-- next step --\n").append(sub.getNextStep()).append('\n');
        }
        if (sub.getFailureReason() != null && !sub.getFailureReason().isBlank()) {
            sb.append("\n-- failure --\n").append(sub.getFailureReason()).append('\n');
        }
        if (!sub.getCheckpoints().isEmpty()) {
            sb.append("\n-- checkpoints (").append(sub.getCheckpoints().size()).append(") --\n");
            for (Checkpoint ck : sub.getCheckpoints()) {
                sb.append("  • ").append(ck.getCheckpointId())
                        .append(ck.isAutomatic() ? " (auto)" : " (manual)")
                        .append(" — ").append(ck.getNote()).append('\n');
                if (!ck.getFiles().isEmpty()) {
                    sb.append("    files: ").append(String.join(", ", ck.getFiles())).append('\n');
                }
                if (!ck.getFunctions().isEmpty()) {
                    sb.append("    fns:   ").append(String.join(", ", ck.getFunctions())).append('\n');
                }
            }
        }
        ctx.out().print(sb.toString());
        ctx.out().flush();
        return 0;
    }
}
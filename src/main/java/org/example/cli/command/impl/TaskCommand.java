package org.example.cli.command.impl;

import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

/**
 * /task &lt;taskId&gt; —— 显示单个 DAG 节点详情（阶段 2 重写）。
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
        return "show one DAG node detail by id (stage 2)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String taskId = args == null ? "" : args.trim();
        if (taskId.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.RED, "usage: /task <taskId>"));
            ctx.out().flush();
            return 2;
        }
        var graphOpt = orchestrator.activeGraph();
        if (graphOpt.isEmpty()) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no active plan)"));
            ctx.out().flush();
            return 0;
        }
        DagNode node = graphOpt.get().get(taskId);
        if (node == null) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.RED, "node not found: " + taskId));
            ctx.out().flush();
            return 2;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(node.getTaskId()).append("] ")
                .append(node.getState()).append('\n');
        sb.append("title: ").append(node.getTitle()).append('\n');
        if (!node.getDescription().isBlank()) {
            sb.append("description: ").append(node.getDescription()).append('\n');
        }
        if (!node.getExpectedOutput().isBlank()) {
            sb.append("expected: ").append(node.getExpectedOutput()).append('\n');
        }
        sb.append("dependsOn: ").append(node.dependencies()).append('\n');
        sb.append("attempts: ").append(node.getAttempts()).append('\n');
        if (node.getStartedAt() != null) sb.append("startedAt: ").append(node.getStartedAt()).append('\n');
        if (node.getCompletedAt() != null) sb.append("completedAt: ").append(node.getCompletedAt()).append('\n');
        if (node.getLastResult() != null) {
            sb.append("\n-- last result --\n");
            sb.append("status: ").append(node.getLastResult().getStatus()).append('\n');
            sb.append("duration: ").append(node.getLastResult().getDurationMs()).append("ms\n");
            if (!node.getLastResult().getReason().isBlank()) {
                sb.append("reason: ").append(node.getLastResult().getReason()).append('\n');
            }
            if (!node.getLastResult().getArtifacts().isEmpty()) {
                sb.append("artifacts: ").append(node.getLastResult().getArtifacts()).append('\n');
            }
            if (!node.getLastResult().getReport().isBlank()) {
                sb.append("report: ").append(node.getLastResult().getReport()).append('\n');
            }
        }
        ctx.out().print(sb.toString());
        ctx.out().flush();
        return 0;
    }
}
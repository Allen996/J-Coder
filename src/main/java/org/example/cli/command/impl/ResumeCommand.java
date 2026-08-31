package org.example.cli.command.impl;

import org.example.agent.core.task.dag.DagGraph;
import org.example.agent.core.task.dag.DagState;
import org.example.agent.core.task.dag.DagStateRepository;
import org.example.agent.core.task.dag.DagPlanStatus;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * /resume —— 恢复磁盘上的 RUNNING plan（阶段 2 重写）。
 *
 * <p>两个用法：
 * <ul>
 *   <li>无参数：列出 status=RUNNING 的可恢复 plan，提示用户输入 {@code /resume <planId>}</li>
 *   <li>有参数(planId)：从磁盘 load plan 与 dag-state,调用 {@link TaskOrchestrator#resumeActivePlan()}</li>
 * </ul>
 *
 * <p>阶段 2 按 C 决定：plan 与 dag-state 按 sessionId 组织在 {@code .agent/sessions/{sessionId}/}，
 * 不再有跨 session 续做的 {@code adoptPlan}。
 */
@Component
public class ResumeCommand implements SlashCommand {

    private final TaskOrchestrator orchestrator;
    private final DagStateRepository repository;

    public ResumeCommand(TaskOrchestrator orchestrator, DagStateRepository repository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
    }

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "resume a plan by id (stage 2)";
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
        Path sessionsRoot = repository.sessionsRoot();
        if (!Files.exists(sessionsRoot)) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no resumable plan)"));
            ctx.out().flush();
            return 0;
        }
        int count = 0;
        try (Stream<Path> stream = Files.list(sessionsRoot)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir)) continue;
                Path planJson = dir.resolve("plan.json");
                Path stateJson = dir.resolve("dag-state.json");
                if (!Files.exists(planJson)) continue;
                Optional<DagState> stateOpt = Files.exists(stateJson)
                        ? repository.loadDagState(dir.getFileName().toString())
                        : Optional.empty();
                DagPlanStatus status = stateOpt.map(DagState::getStatus).orElse(DagPlanStatus.RUNNING);
                if (status != DagPlanStatus.RUNNING) continue;
                count++;
                Optional<DagGraph> graphOpt = repository.loadPlan(dir.getFileName().toString());
                String goal = graphOpt.map(DagGraph::getGoal).orElse("?");
                String planId = graphOpt.map(DagGraph::getPlanId).orElse("?");
                ctx.out().println(AnsiStyle.wrap(AnsiStyle.CYAN,
                        "  · " + planId + "  session=" + dir.getFileName() + "  goal="
                                + truncate(goal, 60)));
            }
        } catch (Exception ex) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.RED, "list failed: " + ex.getMessage()));
            ctx.out().flush();
            return 1;
        }
        if (count == 0) {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW, "(no resumable plan)"));
        } else {
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.CYAN_BOLD, count + " resumable plan(s):"));
            ctx.out().println(AnsiStyle.wrap(AnsiStyle.GRAY_DIM,
                    "usage: /resume <planId>"));
        }
        ctx.out().flush();
        return 0;
    }

    /**
     * 阶段 2 简化：/resume 仅"列 plan"；真正加载由 create_plan 后续 dispatch_subtask 流程触发，
     * 不再有旧 adoptPlan（plan 与 session 强绑定，按 sessionId 自动定位）。
     */
    private int adoptPlan(String planId, CliContext ctx) {
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                "stage 2 no longer supports /resume <planId> — plans are bound to session, just continue in same session"));
        ctx.out().flush();
        return 2;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
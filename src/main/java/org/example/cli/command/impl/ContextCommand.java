package org.example.cli.command.impl;

import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.observability.PromptDumpObserver;
import org.example.agent.context.project.ProjectContext;
import org.example.agent.context.project.ProjectContextCache;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.AgentTask;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /context —— 实时查看 prompt dump（part3.md 可观测增强）。
 *
 * <p>打印两种视图：
 * <ol>
 *   <li>静态三层概览：System / Project / Session 各自的 token 估算与字段摘要。
 *      （不需要触发 LLM，直接读 {@link ProjectContextCache} + {@link SessionMessageStore}）。</li>
 *   <li>最近一次 LLM 调用实际发出的 prompt：来自 {@link PromptDumpObserver}。</li>
 * </ol>
 *
 * <p>运行时不需要 verbose，/context 总是把当前快照打印一次。
 */
@Component
public class ContextCommand implements SlashCommand {

    private final ContextBuilder contextBuilder;
    private final ContextBudgetPolicy policy;
    private final SessionMessageStore sessionStore;
    private final ProjectContextCache projectContextCache;
    private final PromptDumpObserver promptDumpObserver;

    public ContextCommand(ContextBuilder contextBuilder,
                          ContextBudgetPolicy policy,
                          SessionMessageStore sessionStore,
                          ProjectContextCache projectContextCache,
                          PromptDumpObserver promptDumpObserver) {
        this.contextBuilder = contextBuilder;
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
        this.sessionStore = sessionStore;
        this.projectContextCache = projectContextCache;
        this.promptDumpObserver = promptDumpObserver;
    }

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "show current 3-layer context snapshot + last LLM prompt";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        boolean wantFull = args != null && (args.contains("--full") || args.contains("-f"));
        int maxChars = wantFull ? Integer.MAX_VALUE : 200;

        ctx.out().println();
        ctx.out().println("=== 3-LAYER OVERVIEW ===");
        ctx.out().println();

        // Project layer
        ProjectContext project = projectContextCache.current();
        long projectEst = ContextBudgetPolicy.estimateProjectTokens(project);
        long systemEst = policy.estimateTextTokens(contextBuilder.renderSystem(stubTask(), project));
        long projectPromptEst = policy.estimateTextTokens(contextBuilder.renderProject(project));
        String sessionId = ctx.session().getSessionId();
        long sessionUsed = sessionStore.estimateUsedTokens(sessionId);
        long sessionReserved = policy.sessionReserved();

        ctx.out().printf("  System Layer      budget=%d  actual≈%d tokens%n",
                policy.getSystemReserved(), systemEst);
        ctx.out().printf("  Project Layer     budget=%d  actual≈%d tokens  (sources=%d, keyConfigs=%d)%n",
                policy.getProjectReserved(), projectPromptEst,
                project == null ? 0 : project.getSourceFileCount(),
                project == null ? 0 : project.getKeyConfigFiles().size());
        ctx.out().printf("  Session Layer     used=%d / budget=%d  (%.1f%%)%n",
                sessionUsed, sessionReserved,
                sessionReserved == 0 ? 0 : (sessionUsed * 100.0 / sessionReserved));
        ctx.out().printf("  Context Window    %d (memory=%d, completion=%d)%n",
                policy.getContextWindowMax(),
                policy.getMemoryTokenReservation(),
                policy.getMaxSingleCallCompletion());
        ctx.out().println();

        // Snapshot of last actual LLM prompt
        PromptDumpObserver.Snapshot snap = promptDumpObserver == null ? null : promptDumpObserver.latestSnapshot();
        if (snap == null) {
            ctx.out().println("(no LLM prompt captured yet — 发一条消息让 agent 跑一次)");
        } else {
            ctx.out().printf("=== LAST LLM PROMPT (step=%d) ===%n", snap.getStepIndex());
            if (promptDumpObserver != null) {
                promptDumpObserver.dumpSnapshot(snap, ctx.out(), maxChars, false);
            }
        }
        ctx.out().flush();
        return 0;
    }

    private AgentTask stubTask() {
        return AgentTask.builder()
                .sessionId("context-cmd-stub")
                .input("")
                .role("chat")
                .build();
    }
}
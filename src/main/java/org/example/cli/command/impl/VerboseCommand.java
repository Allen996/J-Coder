package org.example.cli.command.impl;

import org.example.agent.context.observability.PromptDumpObserver;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

/**
 * /verbose —— 切换 verbose 模式（part3.md 可观测增强 / part5.md §11.2 调试模式）。
 *
 * <p>开 → 每次 LLM 调用前把完整 prompt dump 到 REPL 输出（通过 {@link PromptDumpObserver}）。
 * 关闭 → 只保留最新的 snapshot，可通过 /context 按需查看。
 */
@Component
public class VerboseCommand implements SlashCommand {

    private final SessionState session;
    private final PromptDumpObserver promptDumpObserver;

    public VerboseCommand(SessionState session, PromptDumpObserver promptDumpObserver) {
        this.session = session;
        this.promptDumpObserver = promptDumpObserver;
    }

    @Override
    public String name() {
        return "verbose";
    }

    @Override
    public String description() {
        return "toggle verbose mode (dumps full prompt before each LLM call)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        session.toggleVerbose();
        boolean on = session.isVerbose();
        if (promptDumpObserver != null) {
            promptDumpObserver.setVerbose(on);
            if (on) {
                promptDumpObserver.setVerboseWriter(ctx.out());
            } else {
                promptDumpObserver.setVerboseWriter(null);
            }
        }
        ctx.out().println("verbose mode: " + (on ? "on" : "off")
                + (on ? " (full prompt will be dumped before each LLM call)" : ""));
        ctx.out().flush();
        return 0;
    }
}
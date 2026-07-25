package org.example.cli.command.impl;

import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.layer.StaticLayer;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

/**
 * /load —— 刷新 Static Layer（part3.md §6.2 "启动 + /load 时刷新"）。
 *
 * <p>5 行静态层 key 各自独立刷新。本命令不扫描项目（ProjectScanner 不再作为启动组件 —— part3.md §6.7），
 * 只刷新角色 / 工具列表 / 写代码思维链 / 运行时元信息等基本不变的内容。
 */
@Component
public class LoadCommand implements SlashCommand {

    private final ContextBuilder contextBuilder;
    private final StaticLayer staticLayer;
    private final SessionState session;

    public LoadCommand(ContextBuilder contextBuilder, StaticLayer staticLayer, SessionState session) {
        this.contextBuilder = contextBuilder;
        this.staticLayer = staticLayer;
        this.session = session;
    }

    @Override
    public String name() {
        return "load";
    }

    @Override
    public String description() {
        return "refresh static layer (4 keys)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        org.example.agent.core.task.AgentTask stub = org.example.agent.core.task.AgentTask.builder()
                .sessionId(session.getSessionId())
                .input("")
                .role("chat")
                .promptVariables(java.util.Map.of("model", session.getCurrentModel()))
                .build();
        contextBuilder.loadStaticLayer(stub);
        ctx.out().println("load: static layer refreshed");
        ctx.out().printf("  reloaded at: %s%n", staticLayer.lastLoadedAt());
        ctx.out().println("  4 keys:");
        for (org.example.agent.context.layer.ContextKey key : staticLayer.declaredKeys()) {
            org.example.agent.context.layer.ContextEntry e = staticLayer.get(key).orElse(null);
            if (e == null) {
                ctx.out().printf("    %s: (missing)%n", key.wireName());
            } else {
                ctx.out().printf("    %s: text≈%d tokens, source=%s%n",
                        key.wireName(), e.getEstimatedTokens(), e.getSourceRef());
            }
        }
        ctx.out().flush();
        return 0;
    }
}

package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

@Component
public class CostCommand implements SlashCommand {

    // 估算单价（人民币 / 1K tokens）。qwen3-max 当前参考价，写死作为占位。
    // 真实计费按阿里云百炼官方价目调整。
    private static final double CNY_PER_1K_TOKENS = 0.04;

    private final SessionState session;

    public CostCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "cost";
    }

    @Override
    public String description() {
        return "show current session token usage and estimated cost";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        long in = session.getTotalTokensIn().get();
        long out = session.getTotalTokensOut().get();
        long total = in + out;
        double estCny = (total / 1000.0) * CNY_PER_1K_TOKENS;
        ctx.out().printf("session=%s  model=%s%n", session.getSessionId(), session.getCurrentModel());
        ctx.out().printf("tokens in=%d  out=%d  total=%d%n", in, out, total);
        ctx.out().printf("estimated cost: ¥%.4f (rate=¥%.4f/1k tokens)%n", estCny, CNY_PER_1K_TOKENS);
        ctx.out().flush();
        return 0;
    }
}
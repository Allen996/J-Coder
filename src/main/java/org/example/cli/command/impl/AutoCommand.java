package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

@Component
public class AutoCommand implements SlashCommand {

    private final SessionState session;

    public AutoCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "auto";
    }

    @Override
    public String description() {
        return "toggle auto-approve for the current session";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        session.toggleAutoApprove();
        ctx.out().println("auto-approve: " + (session.isAutoApprove() ? "on" : "off"));
        ctx.out().flush();
        return 0;
    }
}
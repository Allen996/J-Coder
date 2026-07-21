package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

@Component
public class VerboseCommand implements SlashCommand {

    private final SessionState session;

    public VerboseCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "verbose";
    }

    @Override
    public String description() {
        return "toggle verbose mode (shows prompt/completion token counts in thoughts)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        session.toggleVerbose();
        ctx.out().println("verbose mode: " + (session.isVerbose() ? "on" : "off"));
        ctx.out().flush();
        return 0;
    }
}
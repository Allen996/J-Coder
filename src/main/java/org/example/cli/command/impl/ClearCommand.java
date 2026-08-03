package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

@Component
public class ClearCommand implements SlashCommand {

    private final SessionState session;

    public ClearCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "clear current session messages (keeps session_id)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        session.getHistoryForExport().clear();
        // ANSI clear screen + cursor home
        ctx.out().print("[2J[H");
        ctx.out().flush();
        return 0;
    }
}
package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

@Component
public class PlanCommand implements SlashCommand {

    private final SessionState session;

    public PlanCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String description() {
        return "enter/exit plan mode [on|off] (Part 1 toggles state only; enforcement is Part 3)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String a = args == null ? "" : args.trim().toLowerCase();
        if (a.equals("on")) {
            session.setPlanMode(true);
        } else if (a.equals("off")) {
            session.setPlanMode(false);
        } else if (a.isEmpty()) {
            session.togglePlanMode();
        } else {
            ctx.out().println("usage: /plan [on|off]");
            ctx.out().flush();
            return 2;
        }
        ctx.out().println("plan mode: " + (session.isPlanMode() ? "on" : "off") +
                " (write-tool enforcement pending Part 3)");
        ctx.out().flush();
        return 0;
    }
}
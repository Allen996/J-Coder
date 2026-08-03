package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

@Component
public class ModelCommand implements SlashCommand {

    private final SessionState session;

    public ModelCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "switch model (Part 1 only updates SessionState; full switch needs Part 2)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String model = args == null ? "" : args.trim();
        if (model.isEmpty()) {
            ctx.out().println("current model: " + session.getCurrentModel());
            ctx.out().flush();
            return 0;
        }
        session.setCurrentModel(model);
        ctx.out().println("switched to " + model + " (actual switch pending Part 2)");
        ctx.out().flush();
        return 0;
    }
}
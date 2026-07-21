package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

@Component
public class ResumeCommand implements SlashCommand {

    @Override
    public String name() {
        return "resume";
    }

    @Override
    public String description() {
        return "resume a historical session [id] (TODO Part 4)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("resume: SQLite-backed session DB not implemented yet (TODO Part 4).");
        ctx.out().flush();
        return 0;
    }
}
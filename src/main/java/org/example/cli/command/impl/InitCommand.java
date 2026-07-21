package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

@Component
public class InitCommand implements SlashCommand {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String description() {
        return "generate CLAUDE.md project configuration (TODO Part 3)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("init: project scanner / CLAUDE.md generation not implemented yet (TODO Part 3).");
        ctx.out().flush();
        return 0;
    }
}
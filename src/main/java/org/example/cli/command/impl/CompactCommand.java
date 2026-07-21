package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

@Component
public class CompactCommand implements SlashCommand {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "manually trigger context compression (TODO Part 3)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("compact: context compression not implemented yet (TODO Part 3).");
        ctx.out().flush();
        return 0;
    }
}
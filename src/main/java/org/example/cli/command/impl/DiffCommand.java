package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

@Component
public class DiffCommand implements SlashCommand {

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String description() {
        return "show all write operation diffs (TODO Part 2)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("diff: write-op diff tracker not implemented yet (TODO Part 2).");
        ctx.out().flush();
        return 0;
    }
}
package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

@Component
public class UndoCommand implements SlashCommand {

    @Override
    public String name() {
        return "undo";
    }

    @Override
    public String description() {
        return "undo the last write operation (TODO Part 2)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("undo: git-stash based undo not implemented yet (TODO Part 2).");
        ctx.out().flush();
        return 0;
    }
}
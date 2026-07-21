package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.jline.reader.EndOfFileException;
import org.springframework.stereotype.Component;

@Component
public class ExitCommand implements SlashCommand {

    @Override
    public String name() {
        return "exit";
    }

    @Override
    public String description() {
        return "exit the CLI (auto-persists session)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("bye.");
        ctx.out().flush();
        // 抛 EndOfFileException 让 ReplLoop 跳出循环（与 Ctrl-D 行为一致）
        throw new EndOfFileException();
    }
}
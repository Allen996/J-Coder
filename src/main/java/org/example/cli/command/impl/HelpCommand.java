package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.command.SlashCommandRegistry;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class HelpCommand implements SlashCommand {

    private final SlashCommandRegistry registry;

    public HelpCommand(@Lazy SlashCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String description() {
        return "list all slash commands";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("Available commands:");
        int maxNameLen = registry.all().stream().mapToInt(c -> c.name().length()).max().orElse(8);
        for (SlashCommand c : registry.all()) {
            ctx.out().printf("  /%-" + maxNameLen + "s  %s%n", c.name(), c.description());
        }
        ctx.out().println();
        ctx.out().println("Input prefixes:");
        ctx.out().println("  /...   slash command");
        ctx.out().println("  !cmd   shell passthrough (whitelisted verbs only)");
        ctx.out().println("  @path  attach a file to the next message");
        ctx.out().flush();
        return 0;
    }
}
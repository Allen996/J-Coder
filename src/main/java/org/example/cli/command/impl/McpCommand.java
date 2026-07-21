package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

@Component
public class McpCommand implements SlashCommand {

    @Override
    public String name() {
        return "mcp";
    }

    @Override
    public String description() {
        return "list loaded MCP tools (Part 2 — none configured in Part 1)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println("no MCP server configured (MCP client added back in Part 2).");
        ctx.out().flush();
        return 0;
    }
}
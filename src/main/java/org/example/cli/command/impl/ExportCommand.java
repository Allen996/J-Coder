package org.example.cli.command.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.session.SessionState;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ExportCommand implements SlashCommand {

    private final SessionState session;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExportCommand(SessionState session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "export";
    }

    @Override
    public String description() {
        return "export current session as JSONL to <path>";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String arg = args == null ? "" : args.trim();
        if (arg.isEmpty()) {
            ctx.out().println("usage: /export <path>");
            ctx.out().flush();
            return 2;
        }
        Path target = Path.of(arg);
        if (!target.isAbsolute()) {
            target = ctx.projectRoot().resolve(arg).normalize();
        }
        Path parent = target.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                ctx.out().println("failed to create parent dir: " + ex.getMessage());
                ctx.out().flush();
                return 3;
            }
        }

        List<String> history = session.getHistoryForExport();
        try {
            StringBuilder content = new StringBuilder();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("ts", Instant.now().toString());
            header.put("session_id", session.getSessionId());
            header.put("model", session.getCurrentModel());
            header.put("tokens_in", session.getTotalTokensIn().get());
            header.put("tokens_out", session.getTotalTokensOut().get());
            content.append(mapper.writeValueAsString(header)).append('\n');
            for (String text : history) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("ts", Instant.now().toString());
                line.put("role", "assistant");
                line.put("content", text);
                content.append(mapper.writeValueAsString(line)).append('\n');
            }
            Files.writeString(target, content.toString(), StandardCharsets.UTF_8);
            ctx.out().println("exported " + history.size() + " messages → " + target);
            ctx.out().flush();
            return 0;
        } catch (IOException ex) {
            log.warn("export failed", ex);
            ctx.out().println("export failed: " + ex.getMessage());
            ctx.out().flush();
            return 3;
        }
    }
}
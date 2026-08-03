package org.example.cli.command;

import lombok.extern.slf4j.Slf4j;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.input.InputRouter;
import org.jline.reader.EndOfFileException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 "/xxx args" 形式的行分发给对应 {@link SlashCommand}。
 *
 * 路由规则：
 *  - 首词作为命令名（不含 /）
 *  - 剩余部分作为 args 透传给命令
 *  - 未知命令 → 打印提示并返回 2
 *
 * 不使用 Picocli：Picocli 的 Callable 模型不适合 REPL 内与 @file / !shell / 纯文本交错。
 * 用一行正则 + HashMap 派发更直接。
 */
@Slf4j
@Component
public class SlashCommandRegistry {

    private static final Pattern LINE_PATTERN = Pattern.compile("^/([\\w-]+)(?:\\s+(.*))?$");

    private final Map<String, SlashCommand> commands = new HashMap<>();

    public SlashCommandRegistry(List<SlashCommand> beans, InputRouter router) {
        if (beans == null) return;
        for (SlashCommand cmd : beans) {
            String name = cmd.name();
            if (name == null || name.isBlank()) {
                log.warn("skip SlashCommand with blank name: {}", cmd.getClass().getName());
                continue;
            }
            commands.put(name, cmd);
        }
        log.info("SlashCommandRegistry initialized with {} commands: {}", commands.size(), sortedNames());
    }

    public List<String> sortedNames() {
        return commands.keySet().stream().sorted().toList();
    }

    public SlashCommand find(String name) {
        return commands.get(name);
    }

    public List<SlashCommand> all() {
        return commands.values().stream().sorted(Comparator.comparing(SlashCommand::name)).toList();
    }

    /**
     * 分发一行 slash 输入。
     *
     * @return 退出码（0/2/3）—— REPL 不强制使用，仅供调用方决策
     */
    public int dispatch(String line, CliContext ctx) {
        if (line == null || line.isBlank()) {
            return 0;
        }
        String trimmed = line.stripLeading();
        Matcher m = LINE_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            ctx.out().println("refused: malformed slash line, expected /^/[command] [args]");
            return 2;
        }
        String name = m.group(1);
        String args = m.group(2) == null ? "" : m.group(2);
        SlashCommand cmd = commands.get(name);
        if (cmd == null) {
            ctx.out().println("unknown command: /" + name);
            ctx.out().println("type /help to list available commands");
            return 2;
        }
        try {
            return cmd.execute(args, ctx);
        } catch (EndOfFileException eof) {
            // /exit 故意抛此异常以跳出 REPL，不当作错误
            throw eof;
        } catch (RuntimeException ex) {
            log.warn("slash command /{} threw: {}", name, ex.getMessage(), ex);
            ctx.out().println("error executing /" + name + ": " + ex.getMessage());
            return 3;
        }
    }
}
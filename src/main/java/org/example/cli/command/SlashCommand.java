package org.example.cli.command;

import org.example.cli.bootstrap.CliContext;

/**
 * Slash 命令接口。所有实现都是 Spring bean（标 @Component），由 {@link SlashCommandRegistry}
 * 在构造时通过 List<SlashCommand> 注入收集。
 *
 *  - name()  返回去掉前导 "/" 的字面量，例如 "help" 对应 /help
 *  - execute(args, ctx)  返回 Picocli 风格的退出码（0=OK, 2=用户错误, 3=运行错误）
 *
 * 命令实现可以读 args 字符串（已剥离命令名部分），并通过 ctx.out 写出。
 */
public interface SlashCommand {

    /** 命令名（不含前导斜杠）。 */
    String name();

    /** 一行简短说明，显示在 /help 列表里。 */
    String description();

    /**
     * @param args 命令名之后的剩余字符串（可能为空）
     * @param ctx  CLI 上下文（runtime / session / writer / projectRoot / env）
     * @return 退出码（0=OK, 2=用户错误, 3=运行错误）
     */
    int execute(String args, CliContext ctx);
}
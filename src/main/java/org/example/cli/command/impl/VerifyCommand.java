package org.example.cli.command.impl;

import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.example.cli.renderer.AnsiStyle;
import org.springframework.stereotype.Component;

/**
 * /verify —— 阶段 2 stub。
 *
 * <p>阶段 2 取消 VERIFY 子任务 + VerifyRunner 概念（不再有 mvn/gradle 自动构建验证）。
 * 改为：主 Agent 在 plan 里派发一个"代码检测 SubAgent"完成验证。
 *
 * <p>本命令暂时是 noop,提示用户改用 dispatch_subtask 派发检查任务。阶段 3 视情况接入。
 */
@Component
public class VerifyCommand implements SlashCommand {

    @Override
    public String name() {
        return "verify";
    }

    @Override
    public String description() {
        return "deprecated in stage 2 — use dispatch_subtask to run a check SubAgent instead";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ctx.out().println(AnsiStyle.wrap(AnsiStyle.YELLOW,
                "/verify is deprecated in stage 2 — use the dispatch_subtask tool "
                        + "to dispatch a 'code review' SubAgent instead"));
        ctx.out().flush();
        return 0;
    }
}
package org.example.cli.bootstrap;

import org.example.agent.core.runtime.AgentRuntime;
import org.example.cli.session.SessionState;
import org.springframework.core.env.ConfigurableEnvironment;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * 注入到 slash 命令的运行时上下文。
 *
 * 字段选择：仅暴露 slash 命令真实需要的依赖（runtime / session / writer / projectRoot / env），
 * 不直接传整个 ApplicationContext，避免命令实现者摸到 Spring 容器。
 */
public record CliContext(
        AgentRuntime runtime,
        SessionState session,
        PrintWriter out,
        Path projectRoot,
        ConfigurableEnvironment env
) {
}
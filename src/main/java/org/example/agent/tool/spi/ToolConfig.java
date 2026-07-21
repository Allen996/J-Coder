package org.example.agent.tool.spi;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.example.agent.tool.file.FileTools;
import org.example.agent.tool.grep.GrepTools;
import org.example.agent.tool.git.GitTools;
import org.example.agent.tool.shell.ShellTools;

/**
 * 工具相关 bean 的集中装配。
 *
 * <p>关键：{@link MethodToolCallbackProvider} 是 Spring AI 把 {@code @Tool} 方法
 * 转成 {@link org.springframework.ai.tool.ToolCallback} 的唯一入口。不显式声明 provider
 * bean 时，{@code List<ToolCallback>} 注入到 runtime 是空集合 —— 模型拿不到工具定义，
 * 就会"幻觉"调工具（自己编一个工具名 + 假结果）。
 *
 * <p>v1 直接列出 4 个工具类。v2 改成按包扫描 {@code org.example.agent.tool.*Tools}，
 * 自动收集所有以 {@code Tools} 结尾的 bean。
 */
@Configuration
public class ToolConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(FileTools fileTools,
                                                    GrepTools grepTools,
                                                    GitTools gitTools,
                                                    ShellTools shellTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(fileTools, grepTools, gitTools, shellTools)
                .build();
    }
}

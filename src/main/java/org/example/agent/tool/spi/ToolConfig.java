package org.example.agent.tool.spi;

import org.example.agent.tool.cache.RecallToolResult;
import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.file.FileTools;
import org.example.agent.tool.git.GitTools;
import org.example.agent.tool.grep.GrepTools;
import org.example.agent.tool.shell.ShellTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工具相关 bean 的集中装配。
 *
 * <p>关键：{@link MethodToolCallbackProvider} 是 Spring AI 把 {@code @Tool} 方法
 * 转成 {@link org.springframework.ai.tool.ToolCallback} 的唯一入口。不显式声明 provider
 * bean 时，{@code List<ToolCallback>} 注入到 runtime 是空集合 —— 模型拿不到工具定义，
 * 就会"幻觉"调工具（自己编一个工具名 + 假结果）。
 *
 * <p>v1 直接列出 5 个工具类(含 {@link RecallToolResult})。v2 改成按包扫描
 * {@code org.example.agent.tool.*Tools},自动收集所有以 {@code Tools} 结尾的 bean。
 */
@Configuration
public class ToolConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(FileTools fileTools,
                                                    GrepTools grepTools,
                                                    GitTools gitTools,
                                                    ShellTools shellTools,
                                                    RecallToolResult recallToolResult) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(fileTools, grepTools, gitTools, shellTools, recallToolResult)
                .build();
    }

    /**
     * 工具调用执行的统一线程池。被 {@code ToolGateway} (用于超时包装) 与 {@code ReActLoop}
     * (用于只读工具并发分发)共享。
     *
     * <p>核心大小由 {@code cli.tool.tool-executor-core} 控制,默认 CPU*2;最大 8。
     * 队列无界以避免丢工具调用;极端情况下靠机器内存兜底。
     */
    @Bean(name = "toolExecutor", destroyMethod = "shutdown")
    public ExecutorService toolExecutor(CliToolProperties properties) {
        int core = properties.resolvedCorePoolSize();
        int max = properties.toolExecutorMax();
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicLong seq = new AtomicLong();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tool-exec-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                core, max,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                tf);
        exec.allowCoreThreadTimeOut(false);
        return exec;
    }
}

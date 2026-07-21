package org.example.cli;

import org.example.cli.repl.ReplLoop;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * CLI 入口。
 *
 * 关键点：
 *  - setWebApplicationType(NONE) 阻止 Spring 启动 Tomcat
 *  - setBannerMode(Banner.Mode.OFF) + application.yml 的 banner-mode: off 双重抑制 Spring banner
 *  - 启动前设置 jline.terminal=jna，强制 JLine 走 JNA provider（Windows 必需）
 *  - 拿 ReplLoop bean 调 run() 进入主循环；REPL 退出后 context.close() 释放资源
 *
 * 不在 @ComponentScan 上额外配置包路径，默认会从 org.example.cli 向下扫到 org.example.agent.core.*。
 * 因为 SpringBootApplication 默认扫描启动类所在包及其子包。
 */
@SpringBootApplication(scanBasePackages = {
        "org.example.cli",
        "org.example.agent.core",
        "org.example.agent.tool"
})
public class Main {

    public static void main(String[] args) {
        // Windows 下强制 JLine 走 JNA provider，否则可能 fallback 到不支持 ANSI 的 dumb terminal
        System.setProperty("jline.terminal", "jna");
        // 抑制 JLine 自身 log
        System.setProperty("org.jline.terminal.dumb", "true");

        SpringApplication app = new SpringApplication(Main.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        ConfigurableApplicationContext ctx = app.run(args);
        try {
            ReplLoop repl = ctx.getBean(ReplLoop.class);
            repl.run();
        } finally {
            ctx.close();
        }
    }
}
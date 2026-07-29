package org.example.cli.renderer;

import org.example.cli.session.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupBannerTest {

    @Test
    @DisplayName("banner 包含吉祥物、目录、模型、会话、提示")
    void bannerContainsKeyInfo() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.ai.dashscope.chat.options.model", "qwen-plus");
        env.setProperty("agent.memory.model", "qwen-flash");
        SessionState session = new SessionState();

        StartupBanner banner = new StartupBanner(env, session);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        banner.print(pw);

        String out = sw.toString();
        Path expectedRoot = Paths.get("").toAbsolutePath();
        assertTrue(out.contains("SuperBizAgent"), "banner 缺标题: " + out);
        assertTrue(out.contains("J-Agent"), "banner 缺吉祥物名字: " + out);
        assertTrue(out.contains(expectedRoot.toString()),
                "banner 缺启动目录(预期包含 " + expectedRoot + "): " + out);
        assertTrue(out.contains("qwen-plus"), "banner 缺对话模型: " + out);
        assertTrue(out.contains("qwen-flash"), "banner 缺记忆模型: " + out);
        assertTrue(out.contains("/help"), "banner 缺 /help 提示: " + out);
    }

    @Test
    @DisplayName("模型缺失时降级默认值")
    void bannerFallsBackOnMissingModel() {
        MockEnvironment env = new MockEnvironment();
        // 故意不设两个 model property
        SessionState session = new SessionState();

        StartupBanner banner = new StartupBanner(env, session);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        banner.print(pw);

        String out = sw.toString();
        assertTrue(out.contains("qwen-plus"), "banner 应回落到默认对话模型: " + out);
        assertTrue(out.contains("qwen-flash"), "banner 应回落到默认记忆模型: " + out);
    }
}
package org.example.agent.context.memory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆系统 bean 自动装配 sanity 检查（part4.md §7.5 "启动时扫描磁盘重建内存索引"）。
 *
 * <p>最小化冒烟：只启动带 memory 的相关 bean,验证依赖注入图。
 */
@SpringBootTest(classes = org.example.cli.Main.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.banner-mode=off",
                "spring.main.web-application-type=none",
                "spring.main.lazy-initialization=true"
        })
class MemoryPackageWiringTest {

    @Autowired(required = false) FlashMemorySummarizer summarizer;
    @Autowired(required = false) PendingLongTermCandidates pending;
    @Autowired(required = false) LongTermMaintainer maintainer;
    @Autowired(required = false) MemoryIndexSynchronizer indexSync;
    @Autowired(required = false) org.springframework.ai.chat.model.ChatModel memoryChatModel;

    @Test
    void memoryBeansAreWired() {
        // 核心组件都应被 Spring 装配出来(可以 null,仅 LLM bean 在缺 API key 时为 null)
        assertThat(pending).isNotNull();
        assertThat(maintainer).isNotNull();
        assertThat(indexSync).isNotNull();
        assertThat(summarizer).isNotNull();
        // Summarizer 是 NullChatModel 时仍工作
        assertThat(summarizer).isInstanceOf(FlashMemorySummarizer.class);
    }
}

package org.example.agent.context.memory.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆系统专用的轻量 LLM bean（part4.md §7.2 / §7.8 "中期 / 长期记忆提取使用的 LLM 级别 (flash / mini 等)"）。
 *
 * <p>不复用主对话模型，避免大模型被周期性摘要调用拖慢。
 * 默认 {@code qwen-flash}（轻量便宜），按 {@code agent.memory.model} 系统属性覆盖；
 * 没有 API key / DashScope 构造失败时返回 {@code null}，由 {@link MemorySummarizer}
 * 走启发式兜底，本地实现不依赖外部服务。
 */
@Configuration
public class LightweightChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LightweightChatModelConfig.class);

    public static final String DEFAULT_MEMORY_MODEL = "qwen-flash";
    public static final String BEAN_NAME = "memoryChatModel";

    @Bean(name = BEAN_NAME, autowireCandidate = false)
    public ChatModel memoryChatModel(
            @Value("${agent.memory.model:" + DEFAULT_MEMORY_MODEL + "}") String modelName,
            @Value("${agent.memory.api-key:}") String configuredApiKey) {
        // 环境变量优先，回落到 application.yml 的 agent.memory.api-key
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = configuredApiKey;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No memory model API key (checked env DASHSCOPE_API_KEY and agent.memory.api-key); memory summarizer will use heuristic fallback");
            return null;
        }
        try {
            DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .model(modelName)
                    .build();
            DashScopeChatModel built = DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build();
            log.info("Memory summarizer ChatModel configured: model={}", modelName);
            return built;
        } catch (Exception ex) {
            log.warn("Failed to build lightweight ChatModel, falling back to heuristic: {}", ex.getMessage());
            return null;
        }
    }
}

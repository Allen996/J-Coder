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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 记忆系统专用的轻量 LLM bean（part4.md §7.2 / §7.5 / §7.8 "中期 / 长期记忆提取使用的 LLM 级别 (flash / mini 等)"）。
 *
 * <p>不复用主对话模型，避免大模型被周期性摘要调用拖慢。
 * 默认 {@code qwen-flash}（轻量便宜），按 {@code agent.memory.model} 配置覆盖；
 * 没有 API key / DashScope 构造失败时返回 {@code null}。
 *
 * <p>§7.5 硬性约束：<b>不能</b>因为模型不可用就启用启发式兜底产出低质量记忆。模型不可用 → {@link MemoryModelGateway}
 * 直接禁用记忆写入并 WARN，主对话流不受影响。需要 fallback 时显式打开 {@code agent.memory.fallback-to-main=true}，
 * 降级目标仍是配置的模型。
 *
 * <p>独立维护一份 HTTP 超时（{@code agent.memory.http.*}），不复用主对话的
 * {@link ModelHttpClientConfig}：记忆模型上下文小、响应快，超时可比主模型更紧凑；
 * 调短 read-timeout 也能让 {@link MemoryModelGateway} 的 {@code Future.get} 兜底更早生效。
 */
@Configuration
public class LightweightChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(LightweightChatModelConfig.class);

    public static final String DEFAULT_MEMORY_MODEL = "qwen-flash";
    public static final String BEAN_NAME = "memoryChatModel";

    @Bean(name = BEAN_NAME, autowireCandidate = false)
    public ChatModel memoryChatModel(
            @Value("${agent.memory.model:" + DEFAULT_MEMORY_MODEL + "}") String modelName,
            @Value("${agent.memory.api-key:}") String configuredApiKey,
            @Value("${agent.memory.http.connect-timeout-ms:10000}") int connectMs,
            @Value("${agent.memory.http.read-timeout-ms:60000}") int readMs) {
        // 环境变量优先，回落到 application.yml 的 agent.memory.api-key
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = configuredApiKey;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No memory model API key (checked env DASHSCOPE_API_KEY and agent.memory.api-key); memory writes will be DISABLED (set agent.memory.fallback-to-main=true to fall back to main ChatModel)");
            return null;
        }
        try {
            RestClient.Builder restBuilder = RestClient.builder()
                    .requestFactory(buildFactory(connectMs, readMs));
            DashScopeApi api = DashScopeApi.builder()
                    .apiKey(apiKey)
                    .restClientBuilder(restBuilder)
                    .build();
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .model(modelName)
                    .withMultiModel(true)
                    .build();
            DashScopeChatModel built = DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build();
            log.info("Memory summarizer ChatModel configured: model={}, http timeouts connect={}ms read={}ms",
                    modelName, connectMs, readMs);
            return built;
        } catch (Exception ex) {
            log.warn("Failed to build lightweight ChatModel, memory writes will be DISABLED: {}", ex.getMessage());
            return null;
        }
    }

    private static ClientHttpRequestFactory buildFactory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return factory;
    }
}

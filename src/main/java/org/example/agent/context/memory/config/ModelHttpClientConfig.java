package org.example.agent.context.memory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.client.RestClientBuilderConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 模型 HTTP 客户端超时配置。
 *
 * <p>Spring AI Alibaba 1.1.0 的 {@code DashScopeChatAutoConfiguration} 通过
 * {@code ObjectProvider<RestClient.Builder>} 接收项目里自定义的 builder;
 * 只要这里注册一个带 {@link ClientHttpRequestFactory} 超时的 builder bean,
 * 自动配置 clone() 后注入到 {@code DashScopeApi} 里,主对话模型的
 * connect / read 超时即真正落到 socket 层。
 *
 * <p>记忆摘要模型走 {@link LightweightChatModelConfig} 自己的 builder(独立超时策略),
 * 不复用本 bean,避免主/记忆互相牵制。
 */
@Configuration
public class ModelHttpClientConfig {

    /**
     * 供 Spring AI 自动配置发现的主对话模型 {@link RestClient.Builder}。
     * 用 {@link RestClientBuilderConfigurer} 保留 Spring Boot 默认的 message converters /
     * URI 模板等设置,只追加我们自己的超时 factory。
     */
    @Bean
    public RestClient.Builder mainRestClientBuilder(
            RestClientBuilderConfigurer configurer,
            @Value("${agent.model.http.connect-timeout-ms:10000}") int connectMs,
            @Value("${agent.model.http.read-timeout-ms:180000}") int readMs) {
        return configurer.configure(RestClient.builder()
                .requestFactory(buildFactory(connectMs, readMs)));
    }

    /** 复用同一个 factory 设置(供将来其它场景直接拿一个带超时的 RestClient)。 */
    @Bean
    public ClientHttpRequestFactory modelRequestFactory(
            @Value("${agent.model.http.connect-timeout-ms:10000}") int connectMs,
            @Value("${agent.model.http.read-timeout-ms:180000}") int readMs) {
        return buildFactory(connectMs, readMs);
    }

    private static ClientHttpRequestFactory buildFactory(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return factory;
    }
}

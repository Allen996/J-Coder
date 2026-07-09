package org.example.agent.core.runtime;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.example.agent.core.task.AgentTask;

/**
 * 把 AgentTask 翻译成 Spring AI Alibaba 的 ReactAgent 的 SPI。
 *
 * 业务方在 Spring 启动期注册一个 @Bean：
 *
 * <pre>
 *   &#064;Bean
 *   public ReactAgentProvider reactAgentProvider(ChatService chatService) {
 *       return task -&gt; {
 *           DashScopeApi api = chatService.createDashScopeApi();
 *           DashScopeChatModel model = chatService.createStandardChatModel(api);
 *           String prompt = chatService.buildSystemPrompt(loadHistory(task));
 *           return chatService.createReactAgent(model, prompt);
 *       };
 *   }
 * </pre>
 *
 * 后续 4.2 阶段：可以把 ChatService 里的 systemPrompt 切到 PromptRegistry，
 * 4.5 阶段可以把 buildMethodToolsArray 切到 ToolGateway，本接口签名无需变化。
 */
@FunctionalInterface
public interface ReactAgentProvider {

    ReactAgent build(AgentTask task);
}

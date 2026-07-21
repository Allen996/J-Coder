package org.example.agent.core.provider;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.example.agent.core.runtime.ReactAgentProvider;
import org.example.agent.core.task.AgentTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个最简的 ReactAgentProvider：把 AgentTask 字段直接拼成 ReactAgent.builder() 调用。
 *
 * 适用 4.1 阶段（Prompt Registry 还没有），把所有模型 / 工具 / 提示词参数放在这里：
 *  - 模型：留空由 Spring AI Alibaba 自动注入
 *  - 工具：通过 Spring 上下文收集 ToolCallbackProvider / MethodTools
 *  - 提示词：使用 AgentTask.promptVariables 拼一段简易系统提示
 *
 * Part 1 修改说明：
 *  - 早期版本用 @Autowired Object[] methodTools 会把容器里所有 bean 都吸进来，
 *    包括 CliRenderer 这类非工具 bean，造成循环依赖。
 *  - 改为 ObjectProvider + 反射过滤（只接受包含 @Tool 方法的对象），Part 2 会扩展。
 *  - Part 1 阶段没有任何 @Tool 类，methodTools 始终为空数组；toolCallbackProvider 也暂未注入。
 *
 * 业务方不需要直接实现 Provider，只需按需覆盖 provider 即可（参见 guide 5 章）。
 */
@Component
public class SpringAiReactAgentProvider implements ReactAgentProvider {

    private final ObjectProvider<Object> beanProvider;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final ChatModel chatModel;

    public SpringAiReactAgentProvider(
            ObjectProvider<Object> beanProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            ChatModel chatModel) {
        this.beanProvider = beanProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatModel = chatModel;
    }

    @Override
    public ReactAgent build(AgentTask task) {
        Map<String, Object> vars = new HashMap<>();
        if (task.getPromptVariables() != null) {
            vars.putAll(task.getPromptVariables());
        }
        if (task.getInput() != null) vars.put("input", task.getInput());
        if (task.getSessionId() != null) vars.put("sessionId", task.getSessionId());
        if (task.getRole() != null) vars.put("role", task.getRole());

        Builder b = ReactAgent.builder()
                .model(this.chatModel)
                .name(safeName(task.getRole(), "intelligent_assistant"))
                .systemPrompt(buildSystemPrompt(task, vars));

        Object[] methodTools = collectToolObjects();
        if (methodTools.length > 0) {
            b.methodTools(methodTools);
        }
        if (toolCallbackProvider != null) {
            ToolCallbackProvider provider = toolCallbackProvider.getIfAvailable();
            if (provider != null) {
                b.tools(provider.getToolCallbacks());
            }
        }
        return b.build();
    }

    /**
     * 收集包含 @Tool 方法的对象（按类扫描）。过滤掉没有 @Tool 注解的 bean，
     * 避免 Spring AI 把任意对象当作工具源。
     */
    private Object[] collectToolObjects() {
        List<Object> tools = new ArrayList<>();
        if (beanProvider == null) return new Object[0];
        for (Object bean : beanProvider) {
            if (bean == null) continue;
            boolean hasToolMethod = false;
            try {
                for (java.lang.reflect.Method m : bean.getClass().getMethods()) {
                    if (m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                        hasToolMethod = true;
                        break;
                    }
                }
            } catch (Exception ex) {
                continue;
            }
            if (hasToolMethod) {
                tools.add(bean);
            }
        }
        return tools.toArray();
    }

    private String buildSystemPrompt(AgentTask task, Map<String, Object> vars) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的智能助手，能调用工具回答用户问题。\n");
        sb.append("当前任务上下文：\n");
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        if (task.getToolAllowList() != null && !task.getToolAllowList().isEmpty()) {
            sb.append("可用工具：").append(String.join(", ", task.getToolAllowList())).append("\n");
        }
        return sb.toString();
    }

    private static String safeName(String role, String fallback) {
        return (role == null || role.isBlank()) ? fallback : role;
    }
}

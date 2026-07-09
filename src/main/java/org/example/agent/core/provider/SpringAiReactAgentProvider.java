package org.example.agent.core.provider;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.example.agent.core.runtime.ReactAgentProvider;
import org.example.agent.core.task.AgentTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 一个最简的 ReactAgentProvider：把 AgentTask 字段直接拼成 ReactAgent.builder() 调用。
 *
 * 适用 4.1 阶段（Prompt Registry 还没有），把所有模型 / 工具 / 提示词参数放在这里：
 *  - 模型：留空由 Spring AI Alibaba 自动注入
 *  - 工具：通过 Spring 上下文收集 ToolCallbackProvider / MethodTools
 *  - 提示词：使用 AgentTask.promptVariables 拼一段简易系统提示
 *
 * 业务方不需要直接实现 Provider，只需按需覆盖 provider 即可（参见 guide 5 章）。
 */
@Component
public class SpringAiReactAgentProvider implements ReactAgentProvider {

    @Autowired(required = false)
    private Object[] methodTools = new Object[0];

    @Autowired(required = false)
    private org.springframework.ai.tool.ToolCallbackProvider toolCallbackProvider;

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
                .name(safeName(task.getRole(), "intelligent_assistant"))
                .systemPrompt(buildSystemPrompt(task, vars));

        if (methodTools != null && methodTools.length > 0) {
            b.methodTools(methodTools);
        }
        if (toolCallbackProvider != null) {
            b.tools(toolCallbackProvider.getToolCallbacks());
        }
        return b.build();
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

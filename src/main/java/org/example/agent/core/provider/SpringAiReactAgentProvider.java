package org.example.agent.core.provider;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.core.runtime.ReactAgentProvider;
import org.example.agent.core.task.AgentTask;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.chat.model.ChatModel;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ReactAgentProvider（part3.md §6.1 两层字典上下文）。
 *
 * <p>关键点：
 * <ul>
 *   <li>System / Dynamic 层 文本部分由 {@link ContextBuilder#build(AgentTask, String)} 装配为 List&lt;Message&gt;，
 *       整体交给 {@code ReactAgent.builder()}.systemPrompt() 不再支持多段，固这里直接用 messages。spring-ai-alibaba
 *       的 ReactAgent 在没有 systemPrompt 时仍可工作（tool 仍可见）。</li>
 *   <li>工具列表（tool schema）由 Spring AI 的 ToolCallback 自动并入 —— 与 Provider 解耦（结构化通道）。</li>
 *   <li>工具物理隔离（阶段 1 引入）：当 {@code AgentTask.agentRole == "subagent"} 时，
 *       扫描到的 bean 中其 {@code @Tool} 方法若对应 {@link org.example.agent.tool.spi.ToolDescriptor#mainAgentOnly()}=true，
 *       则跳过该方法所属 bean，确保 SubAgent 工具注册层拿不到主 Agent 专属工具。</li>
 *   <li>ProjectContextCache 不再被此 provider 依赖（part3.md §6.7 ProjectScanner 不再作为启动组件）。</li>
 * </ul>
 */
@Component
public class SpringAiReactAgentProvider implements ReactAgentProvider {

    private final ObjectProvider<Object> beanProvider;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final ChatModel chatModel;
    private final ContextBuilder contextBuilder;
    private final ToolDescriptorRegistry toolDescriptorRegistry;

    @Autowired
    public SpringAiReactAgentProvider(
            ObjectProvider<Object> beanProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            ChatModel chatModel,
            ContextBuilder contextBuilder,
            ToolDescriptorRegistry toolDescriptorRegistry) {
        this.beanProvider = beanProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatModel = chatModel;
        this.contextBuilder = contextBuilder;
        this.toolDescriptorRegistry = toolDescriptorRegistry;
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
        if (task.getAgentRole() != null) vars.put("agentRole", task.getAgentRole());

        // 两层字典上下文装配
        String systemPrompt = "";
        if (contextBuilder != null) {
            try {
                ContextBuilder.BuiltContext built = contextBuilder.build(task, task.getInput());
                // 提取 system message 作为 systemPrompt（spring-ai-alibaba 仅支持单段）
                if (built != null && built.getMessages() != null) {
                    for (org.springframework.ai.chat.messages.Message m : built.getMessages()) {
                        if (m instanceof org.springframework.ai.chat.messages.SystemMessage sys) {
                            systemPrompt = sys.getText();
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                systemPrompt = legacySystemPrompt(task, vars);
            }
        } else {
            systemPrompt = legacySystemPrompt(task, vars);
        }

        Builder b = ReactAgent.builder()
                .model(this.chatModel)
                .name(safeName(task.getRole(), "intelligent_assistant"))
                .systemPrompt(systemPrompt);

        Object[] methodTools = collectToolObjects(task.getAgentRole());
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
     * 扫描 Spring 容器里所有含 {@code @Tool} 注解的 bean，按 {@code agentRole} 过滤。
     *
     * <p>主 Agent（agentRole=null 或 "main"）：返回所有含 @Tool 的 bean。
     * SubAgent（agentRole="subagent"）：返回的 bean 中所有 @Tool 方法都必须在
     * {@link ToolDescriptorRegistry} 中能找到且对应描述的 {@code mainAgentOnly=false}。
     * 即"只要一个 bean 的任一 @Tool 方法是 mainAgentOnly，整个 bean 就被排除"
     * —— 简单粗暴，避免 bean 内主/Sub 工具混杂。
     */
    Object[] collectToolObjects(String agentRole) {
        if (beanProvider == null) return new Object[0];
        boolean isSubagent = "subagent".equalsIgnoreCase(agentRole);
        Set<String> mainOnlyNames = isSubagent && toolDescriptorRegistry != null
                ? toolDescriptorRegistry.all().stream()
                    .filter(d -> d.mainAgentOnly())
                    .map(d -> d.name())
                    .collect(Collectors.toSet())
                : Set.of();

        List<Object> tools = new ArrayList<>();
        for (Object bean : beanProvider) {
            if (bean == null) continue;
            if (!hasAnyToolMethod(bean)) continue;
            if (isSubagent && beanContainsMainOnlyTool(bean, mainOnlyNames)) {
                continue;
            }
            tools.add(bean);
        }
        return tools.toArray();
    }

    private boolean hasAnyToolMethod(Object bean) {
        try {
            for (Method m : bean.getClass().getMethods()) {
                if (m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private boolean beanContainsMainOnlyTool(Object bean, Set<String> mainOnlyNames) {
        if (mainOnlyNames.isEmpty()) return false;
        try {
            for (Method m : bean.getClass().getMethods()) {
                if (!m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) continue;
                org.springframework.ai.tool.annotation.Tool ann =
                        m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                String toolName = (ann.name() != null && !ann.name().isBlank()) ? ann.name() : m.getName();
                if (mainOnlyNames.contains(toolName)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private String legacySystemPrompt(AgentTask task, Map<String, Object> vars) {
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

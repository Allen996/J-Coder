package org.example.agent.core.provider;

import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.project.ProjectContext;
import org.example.agent.context.project.ProjectContextCache;
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
 * <p>Part 3 改造：把 system prompt 的渲染交给 {@link ContextBuilder}（part3.md §6.4 步骤 1+3），
 * 旧版散装的"通用助手"模板被三层上下文装配器取代。
 *
 * <p>关键点：
 * <ul>
 *   <li>System Layer 的内容（角色描述 + 时间 + 模型 + 项目根 + CLAUDE.md）由 ContextBuilder.renderSystem 生成。</li>
 *   <li>Project Layer 的内容（文件树 + README + 关键配置）由 ContextBuilder.renderProject 生成，作为第二条 system message 注入。</li>
 *   <li>工具列表（tool schema）由 Spring AI 的 ToolCallback 自动并入 —— 与 Provider 解耦。</li>
 * </ul>
 */
@Component
public class SpringAiReactAgentProvider implements ReactAgentProvider {

    private final ObjectProvider<Object> beanProvider;
    private final ObjectProvider<ToolCallbackProvider> toolCallbackProvider;
    private final ChatModel chatModel;
    private final ContextBuilder contextBuilder;
    private final ProjectContextCache projectContextCache;

    @Autowired
    public SpringAiReactAgentProvider(
            ObjectProvider<Object> beanProvider,
            ObjectProvider<ToolCallbackProvider> toolCallbackProvider,
            ChatModel chatModel,
            ContextBuilder contextBuilder,
            ProjectContextCache projectContextCache) {
        this.beanProvider = beanProvider;
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatModel = chatModel;
        this.contextBuilder = contextBuilder;
        this.projectContextCache = projectContextCache;
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

        // 三层上下文装配：System + Project + Session
        ProjectContext project = projectContextCache == null ? null : projectContextCache.current();
        String systemPrompt = contextBuilder == null
                ? legacySystemPrompt(task, vars)
                : contextBuilder.renderSystem(task, project);
        String projectPrompt = contextBuilder == null
                ? ""
                : contextBuilder.renderProject(project);

        String fullSystemPrompt = systemPrompt;
        if (projectPrompt != null && !projectPrompt.isEmpty()) {
            fullSystemPrompt = systemPrompt + "\n\n" + projectPrompt;
        }

        Builder b = ReactAgent.builder()
                .model(this.chatModel)
                .name(safeName(task.getRole(), "intelligent_assistant"))
                .systemPrompt(fullSystemPrompt);

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

    /** Part 1 时代的兜底 system prompt（保留以便测试 / 非 Spring 上下文场景）。 */
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
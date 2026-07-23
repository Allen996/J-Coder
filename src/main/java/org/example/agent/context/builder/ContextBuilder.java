package org.example.agent.context.builder;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.project.FileTreeNode;
import org.example.agent.context.project.ProjectContext;
import org.example.agent.context.project.ProjectContextCache;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.AgentTask;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 三层上下文的统一装配器（part3.md §6.4）。
 *
 * <p>{@link #build(AgentTask, String)} 的流程对应 part3.md §6.4 表格下方列出的 5 步：
 * <ol>
 *   <li>装配 system message（角色描述 + 工具列表 + 时间 / 模型 / 项目根）。</li>
 *   <li>装配工具 schema —— 由调用方（{@link org.example.agent.core.provider.SpringAiReactAgentProvider}）
 *       通过 ToolCallbackProvider 完成；本类不重做这件事。</li>
 *   <li>装配 project context（按文件大小截断）。</li>
 *   <li>装配 session messages，超额时触发
 *       {@link org.example.agent.context.compression.ConversationCompressor}。</li>
 *   <li>仍超 → 抛 {@link ContextOverflowException}，由
 *       {@link org.example.agent.core.observer.TokenBudgetObserver} 终止执行（CONTEXT_OVERFLOW）。</li>
 * </ol>
 *
 * <p>本类不直接调用 LLM，不与 Spring AI Prompt 绑定 —— 输出是 {@link BuiltContext}（List&lt;Message&gt;
 * + 估算 token 用量 + 各层占比），方便上层做不同维度的二次装配（流式 / 同步 / 调试打印）。
 */
@Component
public class ContextBuilder {

    private final ContextBudgetPolicy policy;
    private final SessionMessageStore sessionStore;
    private final ProjectContextSupplier projectContextSupplier;
    private final org.example.agent.context.compression.ConversationCompressor compressor;

    @org.springframework.beans.factory.annotation.Autowired
    public ContextBuilder(ContextBudgetPolicy policy,
                          SessionMessageStore sessionStore,
                          org.example.agent.context.compression.ConversationCompressor compressor,
                          ProjectContextCache projectContextCache) {
        this(policy, sessionStore,
                projectContextCache == null ? () -> null : (ProjectContextSupplier) projectContextCache::current,
                compressor);
    }

    public ContextBuilder(ContextBudgetPolicy policy,
                          SessionMessageStore sessionStore,
                          ProjectContextSupplier projectContextSupplier,
                          org.example.agent.context.compression.ConversationCompressor compressor) {
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
        this.sessionStore = sessionStore;
        this.projectContextSupplier = projectContextSupplier;
        this.compressor = compressor;
    }

    /** 装配上下文。失败抛 {@link ContextOverflowException}。 */
    public BuiltContext build(AgentTask task, String userInput) {
        ProjectContext project = currentProjectContext();
        long systemTokens;
        long projectTokens;
        long sessionTokens;

        List<Message> messages = new ArrayList<>();

        // 1. System Layer
        String systemText = renderSystem(task, project);
        SystemMessage sysMsg = new SystemMessage(systemText);
        messages.add(sysMsg);
        systemTokens = ContextBudgetPolicy.estimateTextTokens(systemText);
        if (systemTokens > policy.getSystemReserved()) {
            // 截断 system 至预算内（不抛错，因为 system 已知内容可控）
            sysMsg = truncateSystem(systemText, policy.getSystemReserved());
            messages.set(0, sysMsg);
            systemTokens = policy.getSystemReserved();
        }

        // 3. Project Layer
        String projectText = renderProject(project);
        if (!projectText.isEmpty()) {
            String truncated = truncateText(projectText, policy.getProjectReserved());
            if (!truncated.isEmpty()) {
                messages.add(new SystemMessage(truncated));
                projectTokens = ContextBudgetPolicy.estimateTextTokens(truncated);
            } else {
                projectTokens = 0L;
            }
        } else {
            projectTokens = 0L;
        }

        // 当前 user input
        if (userInput != null && !userInput.isEmpty()) {
            messages.add(new UserMessage(userInput));
        }

        // 4. Session Layer
        SessionMessageStore.Session session = sessionStore.getOrCreate(task.getSessionId());
        List<Message> history = session.snapshot();
        long sessionReserved = policy.sessionReserved();

        long historyTokens = estimateMessagesTokens(history);
        if (historyTokens > sessionReserved) {
            // 触发压缩
            if (compressor != null) {
                List<Message> compressed = compressor.compress(history, policy, task);
                history = compressed;
                historyTokens = estimateMessagesTokens(history);
            }
            // 仍超 → 抛 overflow
            if (historyTokens > sessionReserved) {
                throw new ContextOverflowException(
                        "session history exceeds reserved budget after compression: "
                                + historyTokens + " > " + sessionReserved,
                        historyTokens, sessionReserved);
            }
        }
        // 把 history 插在 userInput 之前（保留对话顺序）
        if (!history.isEmpty()) {
            int insertIdx = messages.size() - (userInput == null || userInput.isEmpty() ? 0 : 1);
            messages.addAll(insertIdx, history);
        }
        sessionTokens = historyTokens;

        return BuiltContext.builder()
                .task(task)
                .messages(messages)
                .systemText(sysMsg.getText())
                .projectText(projectText)
                .systemTokens(systemTokens)
                .projectTokens(projectTokens)
                .sessionTokens(sessionTokens)
                .sessionReserved(sessionReserved)
                .projectContext(project)
                .builtAt(Instant.now())
                .build();
    }

    /** 渲染 System Layer（part3.md §6.1 第一层）。 */
    public String renderSystem(AgentTask task, ProjectContext project) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的智能助手，能调用工具回答用户问题。\n\n");

        sb.append("## 当前上下文\n");
        sb.append("- 时间: ").append(Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime()).append("\n");
        sb.append("- 模型: ").append(task.getPromptVariables() == null
                ? "qwen-plus" : task.getPromptVariables().getOrDefault("model", "qwen-plus")).append("\n");
        sb.append("- 项目根: ").append(project != null && project.getRoot() != null
                ? project.getRoot().toString() : "(unknown)").append("\n");
        sb.append("- 包管理器: ").append(project != null ? project.getPackageManagerOrDefault() : "unknown").append("\n");

        if (task.getToolAllowList() != null && !task.getToolAllowList().isEmpty()) {
            sb.append("- 可用工具: ").append(String.join(", ", task.getToolAllowList())).append("\n");
        }
        sb.append("\n");

        // CLAUDE.md 注入 —— part3.md §6.3："ProjectScanner 自动加载并注入到 System Layer"
        if (project != null && project.getClaudeMd() != null && !project.getClaudeMd().isEmpty()) {
            sb.append("## 项目约定（CLAUDE.md）\n");
            sb.append(project.getClaudeMd()).append("\n\n");
        }

        return sb.toString();
    }

    /** 渲染 Project Layer（part3.md §6.1 第二层）。 */
    public String renderProject(ProjectContext project) {
        if (project == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目结构（深度 ≤ 3）\n");
        sb.append("(扫描根: ").append(project.getRoot()).append(")\n");
        renderFileTree(sb, project.getFileTree(), 0);
        sb.append("\n");

        if (project.getReadme() != null && !project.getReadme().isEmpty()) {
            sb.append("## README.md (excerpt)\n");
            sb.append(project.getReadme()).append("\n\n");
        }
        if (project.getKeyConfigFiles() != null && !project.getKeyConfigFiles().isEmpty()) {
            sb.append("## 关键配置文件\n");
            for (ProjectContext.KeyConfigFile k : project.getKeyConfigFiles()) {
                sb.append("### ").append(k.getRelativePath()).append(" (").append(k.getType()).append(")\n");
                sb.append("```\n").append(k.getContent()).append("\n```\n\n");
            }
        }
        if (project.isTruncated()) {
            sb.append("> 注：源码文件数超过 1000，已按 mtime 截断\n\n");
        }
        return sb.toString();
    }

    private void renderFileTree(StringBuilder sb, FileTreeNode node, int indent) {
        if (node == null) return;
        if (node.getDepth() == 0 && (node.getChildren() == null || node.getChildren().isEmpty())) {
            // 空树
            sb.append("(empty)\n");
            return;
        }
        if (node.getDepth() > 0) {
            sb.append("  ".repeat(Math.max(0, indent - 1)));
            sb.append(node.isDirectory() ? "📁 " : "📄 ");
            sb.append(node.getName()).append("\n");
        }
        if (node.getChildren() != null) {
            int next = indent + 1;
            for (FileTreeNode c : node.getChildren()) {
                renderFileTree(sb, c, next);
            }
        }
    }

    // ============== 工具方法 ==============

    private ProjectContext currentProjectContext() {
        if (projectContextSupplier != null) {
            return projectContextSupplier.get();
        }
        return null;
    }

    private static long estimateMessagesTokens(List<Message> msgs) {
        long t = 0L;
        for (Message m : msgs) {
            t += ContextBudgetPolicy.estimateTextTokens(SessionMessageStore.extractText(m));
        }
        return t;
    }

    private static String truncateText(String text, long maxTokens) {
        if (text == null || text.isEmpty()) return "";
        long maxChars = Math.max(0L, maxTokens) * 4L;
        if (text.length() <= maxChars) return text;
        return text.substring(0, (int) maxChars) + "\n... (truncated)";
    }

    private static SystemMessage truncateSystem(String text, long maxTokens) {
        return new SystemMessage(truncateText(text, maxTokens));
    }

    // ============ 静态内部类型 ============

    @Getter
    @Builder
    @ToString(of = {"systemTokens", "projectTokens", "sessionTokens", "sessionReserved"})
    public static final class BuiltContext {
        private final AgentTask task;
        private final List<Message> messages;
        private final String systemText;
        private final String projectText;
        private final long systemTokens;
        private final long projectTokens;
        private final long sessionTokens;
        private final long sessionReserved;
        private final ProjectContext projectContext;
        private final Instant builtAt;

        public long totalPromptTokens() {
            return systemTokens + projectTokens + sessionTokens;
        }
    }

    /** 上下文装配失败的不可恢复错误 —— part3.md §6.4 步骤 5。 */
    public static final class ContextOverflowException extends RuntimeException {
        private final long used;
        private final long reserved;

        public ContextOverflowException(String message, long used, long reserved) {
            super(message);
            this.used = used;
            this.reserved = reserved;
        }

        public long getUsed() { return used; }
        public long getReserved() { return reserved; }
    }

    /** 让调用方在需要时动态切换 ProjectContext（如 /load 刷新）。 */
    @FunctionalInterface
    public interface ProjectContextSupplier {
        ProjectContext get();
    }

    /** 预留位 —— 后续可以传入 model 名等运行时信息。 */
    @Builder
    @Getter
    @ToString
    public static final class ContextBuilderDependencies {
        private final String modelName;
        private final String projectRootOverride;
    }

    /** 测试 / 工厂钩子：把 Spring 注入的 ProjectContext 包装成 supplier。 */
    public static ProjectContextSupplier fixed(ProjectContext ctx) {
        return () -> ctx;
    }

    /** 静态助手：让 SpringAiReactAgentProvider 可以构造一个最小的 builder（用于无 session 场景）。 */
    public static Message assistantPlaceholder(String text) {
        return new AssistantMessage(text == null ? "" : text);
    }
}
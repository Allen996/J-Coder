package org.example.agent.context.builder;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.layer.DynamicLayer;
import org.example.agent.context.layer.LayerSeparator;
import org.example.agent.context.layer.StaticLayer;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.memory.MidTermStore;
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
 * 两层字典上下文的统一装配器（part3.md §6.1）。
 *
 * <p>装配流程（part3.md §6.4 步骤 1-5）：
 * <ol>
 *   <li>装配 Static Layer 4 个 key（role_definition / tool_list / code_writing_cot / runtime_meta）。</li>
 *   <li>工具 schema 由 Spring AI toolCallbacks 注入，不占文本预算（结构化通道）。</li>
 *   <li>装配 Dynamic Layer 5 个 key（messages / mid_term / long_term / memory_index / ephemeral）。</li>
 *   <li>messages 加载按"最近 5 轮递减 + 单轮 LLM 压缩"算法（{@link ConversationCompressor#loadMessages}）。</li>
 *   <li>仍超 → 抛 {@link ContextOverflowException}。</li>
 * </ol>
 *
 * <p>每层按 key 独立组织：修改一个 key 不触发其他 key 的重渲染；
 * 每个 key 自带序列化通道（文本走 SystemMessage / 结构化走 Spring AI 工具回调）。
 */
@Component
public class ContextBuilder {

    private final ContextBudgetPolicy policy;
    private final SessionMessageStore sessionStore;
    private final ConversationCompressor compressor;
    private final StaticLayer staticLayer;
    private final DynamicLayer dynamicLayer;
    private final MidTermStore midTermStore;
    private final LongTermStore longTermStore;
    private final MemoryIndex memoryIndex;

    @org.springframework.beans.factory.annotation.Autowired
    public ContextBuilder(ContextBudgetPolicy policy,
                          SessionMessageStore sessionStore,
                          ConversationCompressor compressor,
                          StaticLayer staticLayer,
                          DynamicLayer dynamicLayer,
                          MidTermStore midTermStore,
                          LongTermStore longTermStore,
                          MemoryIndex memoryIndex) {
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
        this.sessionStore = sessionStore;
        this.compressor = compressor == null ? new ConversationCompressor() : compressor;
        this.staticLayer = staticLayer == null ? new StaticLayer() : staticLayer;
        this.dynamicLayer = dynamicLayer == null ? new DynamicLayer() : dynamicLayer;
        this.midTermStore = midTermStore;
        this.longTermStore = longTermStore;
        this.memoryIndex = memoryIndex;
    }

    /** 构造一个无 Spring 依赖的最小 builder（用于测试 / 单装配场景）。 */
    public static ContextBuilder minimal(ContextBudgetPolicy policy,
                                          SessionMessageStore sessionStore,
                                          ConversationCompressor compressor) {
        return new ContextBuilder(policy, sessionStore, compressor,
                new StaticLayer(), new DynamicLayer(),
                new MidTermStore(), new LongTermStore(), new MemoryIndex());
    }

    /** 装配上下文。失败抛 {@link ContextOverflowException}。 */
    public BuiltContext build(AgentTask task, String userInput) {
        // 1. Static Layer —— 4 个 key 各自独立写一次
        loadStaticLayer(task);

        // 2. Dynamic Layer —— 5 个 key 各自独立加载
        loadDynamicLayer(task, userInput);

        // 3. 顺序拼接：Static (SystemMessage 文本) + Dynamic 分界 + 5 个 dynamic key
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(renderStaticText()));

        // dynamic 层在装配时按顺序追加：messages -> mid_term -> long_term -> memory_index -> ephemeral
        // 各自从 dynamicLayer 拿
        for (ContextKey key : dynamicLayer.declaredKeys()) {
            ContextEntry entry = dynamicLayer.get(key).orElse(null);
            if (entry == null) continue;
            String text = entry.getText();
            if (text == null || text.isEmpty()) continue;
            String header = renderDynamicKeyHeader(key);
            messages.add(new SystemMessage(header + "\n" + text));
        }

        // 4. 用户当前输入
        if (userInput != null && !userInput.isEmpty()) {
            messages.add(new UserMessage(userInput));
        }

        // 5. 估算：static 4 key + dynamic 5 key + user input
        long staticTokens = staticLayer.totalEstimatedTokens();
        long dynamicTokens = dynamicLayer.totalEstimatedTokens();
        long inputTokens = ContextBudgetPolicy.estimateTextTokens(userInput);
        long total = staticTokens + dynamicTokens + inputTokens;

        BuiltContext result = BuiltContext.builder()
                .task(task)
                .messages(messages)
                .staticLayer(staticLayer)
                .dynamicLayer(dynamicLayer)
                .staticTokens(staticTokens)
                .dynamicTokens(dynamicTokens)
                .dynamicReserved(policy.dynamicReserved())
                .inputTokens(inputTokens)
                .totalTokens(total)
                .builtAt(Instant.now())
                .build();

        // 6. 总超 → 抛 overflow
        if (total > policy.getContextWindowMax()) {
            throw new ContextOverflowException(
                    "total context exceeds window: " + total + " > " + policy.getContextWindowMax(),
                    total, policy.getContextWindowMax());
        }
        return result;
    }

    // ============ Static Layer 装配 ============

    /** 装配 / 刷新 Static Layer 4 个 key。每次 /load 都会调用。 */
    public void loadStaticLayer(AgentTask task) {
        // role_definition —— 文本通道
        staticLayer.put(ContextKey.ROLE_DEFINITION, ContextEntry.builder()
                .key(ContextKey.ROLE_DEFINITION)
                .text(renderRoleDefinition())
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(renderRoleDefinition()))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://role_definition")
                .build());

        // tool_list —— 结构化通道（Spring AI toolCallbacks 注入，不占文本预算）
        // 这里仅记录元信息：当前 session 启用的工具名
        String toolListText = renderToolListText(task);
        staticLayer.put(ContextKey.TOOL_LIST, ContextEntry.builder()
                .key(ContextKey.TOOL_LIST)
                .structuredPayload(null)
                .text(toolListText.isEmpty() ? null : toolListText)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(toolListText))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://tool_list")
                .build());

        // code_writing_cot —— 文本通道
        String cot = renderCodeWritingCot();
        staticLayer.put(ContextKey.CODE_WRITING_COT, ContextEntry.builder()
                .key(ContextKey.CODE_WRITING_COT)
                .text(cot)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(cot))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://code_writing_cot")
                .build());

        // runtime_meta —— 文本通道
        String meta = renderRuntimeMeta(task);
        staticLayer.put(ContextKey.RUNTIME_META, ContextEntry.builder()
                .key(ContextKey.RUNTIME_META)
                .text(meta)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(meta))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://runtime_meta")
                .build());

        staticLayer.markLoaded();
    }

    /** 渲染 Static Layer 文本部分（不包含 tool_list 的结构化通道）。 */
    String renderStaticText() {
        StringBuilder sb = new StringBuilder();
        sb.append("## STATIC LAYER ").append(LayerSeparator.render()).append("\n\n");
        for (ContextKey key : staticLayer.declaredKeys()) {
            if (key == ContextKey.TOOL_LIST) continue; // 结构化通道
            ContextEntry entry = staticLayer.get(key).orElse(null);
            if (entry == null || entry.getText() == null || entry.getText().isEmpty()) continue;
            sb.append("### ").append(key.wireName()).append("\n");
            sb.append(entry.getText()).append("\n\n");
        }
        return sb.toString();
    }

    public static String renderRoleDefinition() {
        return "你是一个专业的智能助手，能调用工具回答用户问题。\n"
                + "风格：严谨、客观、可追溯。\n"
                + "能力：阅读项目代码、搜索信息、修改文件、执行命令、调用工具。";
    }

    public static String renderToolListText(AgentTask task) {
        if (task == null || task.getToolAllowList() == null || task.getToolAllowList().isEmpty()) {
            return "";
        }
        return String.join(", ", task.getToolAllowList());
    }

    public static String renderCodeWritingCot() {
        return "写代码时请遵循以下思维链：\n"
                + "1. 先理解用户意图（不要急着动手）\n"
                + "2. 用 search/read 工具探查现有代码与上下文\n"
                + "3. 设计最小可工作改动，必要时列出方案\n"
                + "4. 实施改动，遵循项目的风格约定（参考 long_term 记忆）\n"
                + "5. 自我验证：编译 / 测试 / 边界检查\n"
                + "6. 总结：做了什么 / 为什么 / 后续可选优化";
    }

    public static String renderRuntimeMeta(AgentTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 时间: ").append(Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime()).append("\n");
        sb.append("- 模型: ").append(task == null || task.getPromptVariables() == null
                ? "qwen-plus" : task.getPromptVariables().getOrDefault("model", "qwen-plus")).append("\n");
        return sb.toString();
    }

    // ============ Dynamic Layer 装配 ============

    public void loadDynamicLayer(AgentTask task, String userInput) {
        // 1. messages —— 5 轮递减 + 单轮 LLM 压缩
        if (sessionStore != null) {
            SessionMessageStore.Session session = sessionStore.getOrCreate(task.getSessionId());
            List<Message> history = session.snapshot();
            List<Message> messages = compressor.loadMessages(history, policy, task);
            String text = renderMessagesAsText(messages);
            dynamicLayer.put(ContextKey.MESSAGES, ContextEntry.builder()
                    .key(ContextKey.MESSAGES)
                    .text(text)
                    .structuredPayload(messages)
                    .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(text))
                    .lastRefreshedAt(Instant.now())
                    .sourceRef("session://" + task.getSessionId())
                    .build());
        } else {
            dynamicLayer.put(ContextKey.MESSAGES, ContextEntry.empty(ContextKey.MESSAGES));
        }

        // 2. mid_term —— Session 整体总结；按相关性隐式匹配返回最相关 N 条
        if (midTermStore != null) {
            MidTermStore.MidTerm mt = midTermStore.loadOrEmpty(task.getSessionId());
            String text = mt == null ? "" : mt.toMarkdown();
            if (ContextBudgetPolicy.estimateTextTokens(text) > policy.getMidTermQuota()) {
                text = truncate(text, policy.getMidTermQuota());
            }
            dynamicLayer.put(ContextKey.MID_TERM, ContextEntry.builder()
                    .key(ContextKey.MID_TERM)
                    .text(text.isEmpty() ? null : text)
                    .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(text))
                    .lastRefreshedAt(Instant.now())
                    .sourceRef("memory://mid_term")
                    .build());
        } else {
            dynamicLayer.put(ContextKey.MID_TERM, ContextEntry.empty(ContextKey.MID_TERM));
        }

        // 3. long_term —— 从 Nico.md 全量加载
        if (longTermStore != null) {
            java.util.List<LongTermStore.Entry> entries = longTermStore.loadOrEmpty();
            String text = renderLongTerm(entries);
            if (ContextBudgetPolicy.estimateTextTokens(text) > policy.getLongTermQuota()) {
                text = truncate(text, policy.getLongTermQuota());
            }
            dynamicLayer.put(ContextKey.LONG_TERM, ContextEntry.builder()
                    .key(ContextKey.LONG_TERM)
                    .text(text.isEmpty() ? null : text)
                    .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(text))
                    .lastRefreshedAt(Instant.now())
                    .sourceRef("memory://Nico.md")
                    .build());
        } else {
            dynamicLayer.put(ContextKey.LONG_TERM, ContextEntry.empty(ContextKey.LONG_TERM));
        }

        // 4. memory_index —— MEMORY.md LRU 20；常驻动态层，压缩阶段不被处理
        if (memoryIndex != null) {
            java.util.List<MemoryIndex.IndexEntry> entries = memoryIndex.loadOrEmpty();
            String text = renderMemoryIndex(memoryIndex.matchTopN(userInput, policy.getMidTermTopN()));
            if (ContextBudgetPolicy.estimateTextTokens(text) > policy.getMemoryIndexQuota()) {
                text = truncate(text, policy.getMemoryIndexQuota());
            }
            dynamicLayer.put(ContextKey.MEMORY_INDEX, ContextEntry.builder()
                    .key(ContextKey.MEMORY_INDEX)
                    .text(text.isEmpty() ? null : text)
                    .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(text))
                    .lastRefreshedAt(Instant.now())
                    .sourceRef("memory://MEMORY.md")
                    .build());
        } else {
            dynamicLayer.put(ContextKey.MEMORY_INDEX, ContextEntry.empty(ContextKey.MEMORY_INDEX));
        }

        // 5. ephemeral —— 每个 step 重建清理；本 class 不写，step 进入时由 step observer 写入
        dynamicLayer.put(ContextKey.EPHEMERAL, ContextEntry.empty(ContextKey.EPHEMERAL));
    }

    static String renderMessagesAsText(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            String role;
            if (m instanceof UserMessage) role = "user";
            else if (m instanceof AssistantMessage) role = "assistant";
            else if (m instanceof SystemMessage) role = "system";
            else role = m.getClass().getSimpleName();
            sb.append("[").append(role).append("]\n");
            sb.append(SessionMessageStore.extractText(m)).append("\n\n");
        }
        return sb.toString();
    }

    static String renderLongTerm(java.util.List<LongTermStore.Entry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (LongTermStore.Entry e : entries) {
            sb.append("- [").append(e.getCategory().display).append("] (imp=").append(e.getImportance()).append(") ");
            sb.append(e.getContent()).append("\n");
        }
        return sb.toString();
    }

    static String renderMemoryIndex(java.util.List<MemoryIndex.IndexEntry> entries) {
        if (entries == null || entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MemoryIndex.IndexEntry e : entries) {
            sb.append("- ").append(e.toLine()).append("\n");
        }
        return sb.toString();
    }

    static String renderDynamicKeyHeader(ContextKey key) {
        return "## " + key.wireName() + " " + LayerSeparator.render();
    }

    private static String truncate(String text, long maxTokens) {
        if (text == null || text.isEmpty()) return "";
        long maxChars = Math.max(0L, maxTokens) * 4L;
        if (text.length() <= maxChars) return text;
        return text.substring(0, (int) maxChars) + "\n... (truncated)";
    }

    // ============ 静态内部类型 ============

    @Getter
    @Builder
    @ToString(of = {"staticTokens", "dynamicTokens", "dynamicReserved", "totalTokens"})
    public static final class BuiltContext {
        private final AgentTask task;
        private final List<Message> messages;
        private final StaticLayer staticLayer;
        private final DynamicLayer dynamicLayer;
        private final long staticTokens;
        private final long dynamicTokens;
        private final long dynamicReserved;
        private final long inputTokens;
        private final long totalTokens;
        private final Instant builtAt;

        public long totalPromptTokens() {
            return staticTokens + dynamicTokens + inputTokens;
        }
    }

    /** 装配失败 —— 上下文总 token 已超出窗口。 */
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

    /** 预留位 —— 后续可以传入 model 名等运行时信息。 */
    @Builder
    @Getter
    @ToString
    public static final class ContextBuilderDependencies {
        private final String modelName;
        private final String projectRootOverride;
    }

    /** 静态助手：让 SpringAiReactAgentProvider 可以构造一个最小的 builder。 */
    public static Message assistantPlaceholder(String text) {
        return new AssistantMessage(text == null ? "" : text);
    }

    // ============ 兼容旧接口（保持向后兼容）==============

    /** @deprecated 使用 build() 即可，project layer 已被 dynamic 层替代。 */
    @Deprecated
    public String renderSystem(AgentTask task, Object project) {
        loadStaticLayer(task);
        return renderStaticText();
    }

    /** @deprecated 使用 build() 即可。 */
    @Deprecated
    public String renderProject(Object project) {
        return "";
    }
}

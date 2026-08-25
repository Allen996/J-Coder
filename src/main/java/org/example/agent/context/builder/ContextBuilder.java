package org.example.agent.context.builder;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.layer.DynamicLayer;
import org.example.agent.context.layer.LayerSeparator;
import org.example.agent.context.layer.StaticLayer;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.memory.MemoryRecallScorer;
import org.example.agent.context.memory.MidTermStore;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.AgentTask;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.example.agent.core.task.context.TaskPlanContextAssembler;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 两层字典上下文的统一装配器（part3.md §6.1 + part4.md §7.7 召回评分）。
 *
 * <p>装配流程：
 * <ol>
 *   <li>装配 Static Layer 4 个 key（role_definition / tool_list / code_writing_cot / runtime_meta）。</li>
 *   <li>工具 schema 由 Spring AI toolCallbacks 注入，不占文本预算。</li>
 *   <li>装配 Dynamic Layer 5 个 key（messages / mid_term / long_term / memory_index / ephemeral）。</li>
 *   <li>messages 加载按"最近 5 轮递减 + 单轮 LLM 压缩"算法（{@link ConversationCompressor#loadMessages}）。</li>
 *   <li>memory_index 走 {@link MemoryRecallScorer#score} 加权评分 + 阈值门控，允许为空（part4 §7.7）。</li>
 *   <li>仍超 → 抛 {@link ContextOverflowException}。</li>
 * </ol>
 */
@Slf4j
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
    private final MemoryRecallScorer recallScorer;
    private final TaskPlanContextAssembler taskPlanAssembler;

    @Autowired
    public ContextBuilder(ContextBudgetPolicy policy,
                          SessionMessageStore sessionStore,
                          ConversationCompressor compressor,
                          StaticLayer staticLayer,
                          DynamicLayer dynamicLayer,
                          MidTermStore midTermStore,
                          LongTermStore longTermStore,
                          MemoryIndex memoryIndex,
                          TaskPlanContextAssembler taskPlanAssembler) {
        this(policy, sessionStore, compressor, staticLayer, dynamicLayer,
                midTermStore, longTermStore, memoryIndex, null, taskPlanAssembler);
    }

    public ContextBuilder(ContextBudgetPolicy policy,
                          SessionMessageStore sessionStore,
                          ConversationCompressor compressor,
                          StaticLayer staticLayer,
                          DynamicLayer dynamicLayer,
                          MidTermStore midTermStore,
                          LongTermStore longTermStore,
                          MemoryIndex memoryIndex,
                          MemoryRecallScorer recallScorer,
                          TaskPlanContextAssembler taskPlanAssembler) {
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
        this.sessionStore = sessionStore;
        this.compressor = compressor == null ? new ConversationCompressor() : compressor;
        this.staticLayer = staticLayer == null ? new StaticLayer() : staticLayer;
        this.dynamicLayer = dynamicLayer == null ? new DynamicLayer() : dynamicLayer;
        this.midTermStore = midTermStore;
        this.longTermStore = longTermStore;
        this.memoryIndex = memoryIndex;
        this.recallScorer = recallScorer;
        this.taskPlanAssembler = taskPlanAssembler;
    }

    /** 构造一个无 Spring 依赖的最小 builder（用于测试 / 单装配场景）。 */
    public static ContextBuilder minimal(ContextBudgetPolicy policy,
                                          SessionMessageStore sessionStore,
                                          ConversationCompressor compressor) {
        return new ContextBuilder(policy, sessionStore, compressor,
                new StaticLayer(), new DynamicLayer(),
                new MidTermStore(), new LongTermStore(), new MemoryIndex(),
                null, null);
    }

    /** 装配上下文。失败抛 {@link ContextOverflowException}。 */
    public BuiltContext build(AgentTask task, String userInput) {
        // 1. Static Layer —— 4 个 key 各自独立写一次
        loadStaticLayer(task);

        // 2. Dynamic Layer —— 5 个 key 各自独立加载
        loadDynamicLayer(task, userInput);

        // 3. 同步自动压缩:static + dynamic + input > 80% 上下文窗口时,主循环阻塞压缩 session 历史。
        //    这里有意落在主循环同步路径上(ReActLoop.buildInitialMessages -> ContextBuilder.build),
        //    与 part3.md §6.6 一致:压缩的最长耗时是一次 LLM 摘要调用(几百 ms)。
        long staticTokens = staticLayer.totalEstimatedTokens();
        long dynamicTokens = dynamicLayer.totalEstimatedTokens();
        long inputTokens = ContextBudgetPolicy.estimateTextTokens(userInput);
        long autoThreshold = (long) (policy.getContextWindowMax() * policy.getCompressionThreshold());
        long total = staticTokens + dynamicTokens + inputTokens;
        if (total > autoThreshold) {
            log.info("ContextBuilder auto-compression: total={} > {} ({}% of window={}); syncing...",
                    total, autoThreshold,
                    (int) Math.round(policy.getCompressionThreshold() * 100),
                    policy.getContextWindowMax());
            total = syncCompressMessages(task, staticTokens, inputTokens);
            dynamicTokens = dynamicLayer.totalEstimatedTokens();
        }

        // 4. 顺序拼接
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(renderStaticText()));

        for (ContextKey key : dynamicLayer.declaredKeys()) {
            ContextEntry entry = dynamicLayer.get(key).orElse(null);
            if (entry == null) continue;
            String text = entry.getText();
            if (text == null || text.isEmpty()) continue;
            String header = renderDynamicKeyHeader(key);
            messages.add(new SystemMessage(header + "\n" + text));
        }

        if (userInput != null && !userInput.isEmpty()) {
            messages.add(new UserMessage(userInput));
        }

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

        if (total > policy.getContextWindowMax()) {
            throw new ContextOverflowException(
                    "total context exceeds window: " + total + " > " + policy.getContextWindowMax(),
                    total, policy.getContextWindowMax());
        }
        return result;
    }

    /**
     * 同步自动压缩 session 历史:在主循环同步路径上调用,阻塞最长一次 LLM 摘要。
     * 仅对 messages 层产生影响,其它动态层 key 已由 {@link #loadDynamicLayer} 用配额截断。
     * @return 压缩后新的 total(static + dynamic + input)
     */
    private long syncCompressMessages(AgentTask task, long staticTokens, long inputTokens) {
        if (sessionStore == null) {
            throw new ContextOverflowException(
                    "auto-compression requested but sessionStore is null",
                    staticTokens + inputTokens, policy.getContextWindowMax());
        }
        // 触发时的目标:总上下文 ≤ 80% 上下文窗口。即 messagesBudget = 80%*window − static − input。
        long targetTotal = (long) (policy.getContextWindowMax() * policy.getCompressionThreshold());
        long messagesBudget = targetTotal - staticTokens - inputTokens;
        if (messagesBudget <= 0L) {
            throw new ContextOverflowException(
                    "static+input already exceeds compression threshold budget ("
                            + (staticTokens + inputTokens) + " > " + targetTotal + ")",
                    staticTokens + inputTokens, policy.getContextWindowMax());
        }
        SessionMessageStore.Session session = sessionStore.getOrCreate(task.getSessionId());
        long beforeUsed = sessionStore.estimateUsedTokens(task.getSessionId());
        List<Message> history = session.snapshot();
        if (history.isEmpty()) {
            log.info("ContextBuilder auto-compression: history empty, no-op (total still over threshold)");
            return staticTokens + dynamicLayer.totalEstimatedTokens() + inputTokens;
        }
        List<Message> compressed;
        try {
            compressed = compressor.loadMessages(history, policy, messagesBudget, task);
        } catch (ContextOverflowException ex) {
            log.warn("ContextBuilder auto-compression: loadMessages overflow, propagating: used={} reserved={}",
                    ex.getUsed(), ex.getReserved());
            throw ex;
        }
        session.replaceAll(compressed);
        String summaryText = compressed.isEmpty() ? ""
                : SessionMessageStore.extractText(compressed.get(0));
        session.markCompressed(summaryText);
        long afterUsed = sessionStore.estimateUsedTokens(task.getSessionId());
        session.recordAutoCompression(beforeUsed, afterUsed);

        // 仅重载 messages 层(mid_term / long_term / memory_index / ephemeral / task_plan
        // 在 loadDynamicLayer 已就位,它们用各自的 quota 截断,不参与自动压缩)。
        if (compressed.isEmpty()) {
            dynamicLayer.put(ContextKey.MESSAGES, ContextEntry.empty(ContextKey.MESSAGES));
        } else {
            String text = renderMessagesAsText(compressed);
            dynamicLayer.put(ContextKey.MESSAGES, ContextEntry.builder()
                    .key(ContextKey.MESSAGES)
                    .text(text)
                    .structuredPayload(compressed)
                    .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(text))
                    .lastRefreshedAt(Instant.now())
                    .sourceRef("session://" + task.getSessionId())
                    .build());
        }
        long newDynamic = dynamicLayer.totalEstimatedTokens();
        long newTotal = staticTokens + newDynamic + inputTokens;
        log.info("ContextBuilder auto-compression: session-used {} -> {} tokens, dynamicTokens={}, total={} (target {})",
                beforeUsed, afterUsed, newDynamic, newTotal, targetTotal);
        return newTotal;
    }

    // ============ Static Layer 装配 ============

    public void loadStaticLayer(AgentTask task) {
        staticLayer.put(ContextKey.ROLE_DEFINITION, ContextEntry.builder()
                .key(ContextKey.ROLE_DEFINITION)
                .text(renderRoleDefinition())
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(renderRoleDefinition()))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://role_definition")
                .build());

        String toolListText = renderToolListText(task);
        staticLayer.put(ContextKey.TOOL_LIST, ContextEntry.builder()
                .key(ContextKey.TOOL_LIST)
                .structuredPayload(null)
                .text(toolListText.isEmpty() ? null : toolListText)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(toolListText))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://tool_list")
                .build());

        String cot = renderCodeWritingCot();
        staticLayer.put(ContextKey.CODE_WRITING_COT, ContextEntry.builder()
                .key(ContextKey.CODE_WRITING_COT)
                .text(cot)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(cot))
                .lastRefreshedAt(Instant.now())
                .sourceRef("static://code_writing_cot")
                .build());

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

    String renderStaticText() {
        StringBuilder sb = new StringBuilder();
        sb.append("## STATIC LAYER ").append(LayerSeparator.render()).append("\n\n");
        for (ContextKey key : staticLayer.declaredKeys()) {
            if (key == ContextKey.TOOL_LIST) continue;
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
                ? "qwen3.7-plus" : task.getPromptVariables().getOrDefault("model", "qwen3.7-plus")).append("\n");
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

        // 2. mid_term —— 当前 session 始终注入（4 字段 JSON 形态）
        if (midTermStore != null) {
            MidTermStore.MidTerm mt = midTermStore.loadOrEmpty(task.getSessionId());
            String text = mt == null ? "" : mt.toCompactText();
            if (ContextBudgetPolicy.estimateTextTokens(text) > policy.getMidTermQuota()) {
                text = truncate(text, policy.getMidTermQuota());
            }
            dynamicLayer.put(ContextKey.MID_TERM, ContextEntry.builder()
                    .key(ContextKey.MID_TERM)
                    .text(text.isEmpty() ? null : text)
                    .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(text))
                    .lastRefreshedAt(Instant.now())
                    .sourceRef("memory://mid_term/" + task.getSessionId())
                    .build());
        } else {
            dynamicLayer.put(ContextKey.MID_TERM, ContextEntry.empty(ContextKey.MID_TERM));
        }

        // 3. long_term —— pinned/importance=5 条目常驻（recaller 内置处理）
        String longTermText = renderLongTermPinned();
        if (ContextBudgetPolicy.estimateTextTokens(longTermText) > policy.getLongTermQuota()) {
            longTermText = truncate(longTermText, policy.getLongTermQuota());
        }
        dynamicLayer.put(ContextKey.LONG_TERM, ContextEntry.builder()
                .key(ContextKey.LONG_TERM)
                .text(longTermText.isEmpty() ? null : longTermText)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(longTermText))
                .lastRefreshedAt(Instant.now())
                .sourceRef("memory://long_term")
                .build());

        // 4. memory_index —— MemoryRecallScorer 评分召回（part4 §7.7，可为空）
        String memoryIndexText = renderMemoryIndexScored(task.getSessionId(), userInput);
        if (ContextBudgetPolicy.estimateTextTokens(memoryIndexText) > policy.getMemoryIndexQuota()) {
            memoryIndexText = truncate(memoryIndexText, policy.getMemoryIndexQuota());
        }
        dynamicLayer.put(ContextKey.MEMORY_INDEX, ContextEntry.builder()
                .key(ContextKey.MEMORY_INDEX)
                .text(memoryIndexText.isEmpty() ? null : memoryIndexText)
                .estimatedTokens(ContextBudgetPolicy.estimateTextTokens(memoryIndexText))
                .lastRefreshedAt(Instant.now())
                .sourceRef("memory://recall")
                .build());

        // 5. ephemeral —— 占位
        dynamicLayer.put(ContextKey.EPHEMERAL, ContextEntry.empty(ContextKey.EPHEMERAL));

        // 6. task_plan
        if (taskPlanAssembler != null) {
            taskPlanAssembler.assemble(dynamicLayer);
        } else {
            dynamicLayer.put(ContextKey.TASK_PLAN, ContextEntry.empty(ContextKey.TASK_PLAN));
        }
    }

    /** 渲染 pinned / importance=5 的长期条目（常驻注入，不参与评分）。 */
    String renderLongTermPinned() {
        if (recallScorer != null) {
            return MemoryRecallScorer.render(recallScorer.loadPinnedEntries(), 0);
        }
        // 退化路径：没有 scorer 时仅渲染 importance=5 的条目
        if (longTermStore == null) return "";
        StringBuilder sb = new StringBuilder();
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            if (!t.isPinned() && t.getImportance() < 5) continue;
            for (LongTermStore.Entry e : t.getEntries()) {
                if (e.isDeprecated()) continue;
                sb.append("- [").append(e.getCategory().wire).append("] (imp=").append(e.getImportance())
                        .append(") ").append(e.getContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 走评分器渲染 memory_index 段（可为空）。 */
    String renderMemoryIndexScored(String currentSessionId, String userInput) {
        if (recallScorer != null) {
            List<MemoryRecallScorer.Scored> scored = recallScorer.score(userInput, currentSessionId);
            return MemoryRecallScorer.render(scored, 0.35);
        }
        // 退化路径：使用旧 matchTopN（仅用于没接入 scorer 的测试/兼容场景）
        if (memoryIndex != null) {
            return renderMemoryIndex(memoryIndex.matchTopN(userInput, policy.getMidTermTopN()));
        }
        return "";
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

    public static final class ContextOverflowException extends RuntimeException {
        private final long used;
        private final long reserved;

        public ContextOverflowException(String message, long used, long reserved) {
            super(message);
            this.used = used; //已用
            this.reserved = reserved;
        }

        public long getUsed() { return used; }
        public long getReserved() { return reserved; }
    }

    @Builder
    @Getter
    @ToString
    public static final class ContextBuilderDependencies {
        private final String modelName;
        private final String projectRootOverride;
    }

    public static Message assistantPlaceholder(String text) {
        return new AssistantMessage(text == null ? "" : text);
    }

    // ============ 兼容旧接口 ============

    /** @deprecated 使用 build() 即可。 */
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
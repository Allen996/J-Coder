package org.example.agent.context.memory;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.memory.config.LightweightChatModelConfig;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆系统 LLM 统一网关（part4.md §7.5）。
 *
 * <p>对外暴露三件事：
 * <ul>
 *   <li>{@link #isAvailable()} —— 当前是否有可用模型（专用 bean 或 fallback-to-main）</li>
 *   <li>{@link #call(MemoryPromptRegistry.Profile, Map)} —— 用 profile 渲染并调用 LLM，含重试 / repair</li>
 *   <li>{@link #callRaw(String, String)} —— 原始 system + user 调用，供旧路径或特殊场景</li>
 * </ul>
 *
 * <p>失败处理（§7.5）：
 * <ol>
 *   <li>模型不可用 → {@link #call} 返回 null，不抛异常；调用方据此 no-op</li>
 *   <li>调用异常 / 超时 → 退避重试 1 次；仍失败返回 null</li>
 *   <li>解析失败（部分 profile 内部处理）→ 由 profile 自身决定</li>
 * </ol>
 *
 * <p>可选降级：{@code agent.memory.fallback-to-main=true} 时，专用 bean 缺失可降级到主对话 ChatModel。
 * 该降级目标仍是配置的模型，不违反 §7.5 约束。
 */
@Slf4j
@Component
public class MemoryModelGateway {

    public static final String BEAN_NAME = "memoryModelGateway";

    private final ApplicationContext ctx;
    private final MemoryPromptRegistry registry;
    private final boolean fallbackToMain;
    private final int maxRetries;
    private final long timeoutMs;

    private volatile ChatModel memoryModel;
    private volatile ChatModel mainModel;
    private final AtomicBoolean warnedUnavailable = new AtomicBoolean(false);

    public MemoryModelGateway(ApplicationContext ctx,
                              MemoryPromptRegistry registry,
                              @Value("${agent.memory.fallback-to-main:false}") boolean fallbackToMain,
                              @Value("${agent.memory.retry:1}") int maxRetries,
                              @Value("${agent.memory.timeout-ms:30000}") long timeoutMs) {
        this.ctx = ctx;
        this.registry = registry;
        this.fallbackToMain = fallbackToMain;
        this.maxRetries = Math.max(0, maxRetries);
        this.timeoutMs = Math.max(1000L, timeoutMs);
    }

    @PostConstruct
    public void init() {
        this.memoryModel = resolveBean(LightweightChatModelConfig.BEAN_NAME);
        this.mainModel = resolveMainModel();
        if (!isAvailable() && warnedUnavailable.compareAndSet(false, true)) {
            log.warn("MemoryModelGateway: no LLM available — memory writes disabled (set agent.memory.fallback-to-main=true to enable main-model fallback)");
        } else if (memoryModel != null) {
            log.info("MemoryModelGateway: using memoryChatModel bean");
        } else if (fallbackToMain && mainModel != null) {
            log.info("MemoryModelGateway: memoryChatModel bean missing, falling back to main ChatModel (fallback-to-main=true)");
        }
    }

    private ChatModel resolveBean(String name) {
        try {
            return ctx.getBean(name, ChatModel.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private ChatModel resolveMainModel() {
        try {
            return ctx.getBean(ChatModel.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public boolean isAvailable() {
        if (memoryModel != null) return true;
        if (fallbackToMain && mainModel != null) return true;
        return false;
    }

    /** 当前实际生效的模型（专用 bean 优先，fallback 次之）。 */
    public ChatModel effectiveModel() {
        if (memoryModel != null) return memoryModel;
        if (fallbackToMain) return mainModel;
        return null;
    }

    /** 替换专用模型（测试 / fallback 重新触发用）。 */
    public void setMemoryModel(ChatModel model) {
        this.memoryModel = model;
    }

    /** 用 profile 渲染并调用 LLM。模型不可用返回 null，不抛异常。 */
    public String call(MemoryPromptRegistry.Profile profile, Map<String, ?> vars) {
        if (profile == null) return null;
        ChatModel m = effectiveModel();
        if (m == null) return null;
        String system = profile.getSystemText();
        String user = profile.renderUser(vars == null ? new LinkedHashMap<>() : vars);
        return callInternal(m, system, user);
    }

    /** 原始 system + user 调用，供压缩等不依赖 profile 的场景。 */
    public String callRaw(String systemPrompt, String userPrompt) {
        ChatModel m = effectiveModel();
        if (m == null) return null;
        return callInternal(m, systemPrompt, userPrompt);
    }

    private String callInternal(ChatModel m, String systemPrompt, String userPrompt) {
        Exception last = null;
        int attempts = Math.max(1, maxRetries + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                List<Message> msgs = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isEmpty()) msgs.add(new SystemMessage(systemPrompt));
                if (userPrompt != null && !userPrompt.isEmpty()) msgs.add(new UserMessage(userPrompt));
                ChatResponse resp = withTimeout(() -> m.call(new Prompt(msgs)));
                if (resp == null || resp.getResult() == null) return null;
                AssistantMessage out = resp.getResult().getOutput();
                String text = out == null ? null : out.getText();
                if (text == null) return null;
                return text;
            } catch (Exception ex) {
                last = ex;
                log.debug("MemoryModelGateway call failed (attempt {}/{}): {}", i + 1, attempts, ex.getMessage());
            }
        }
        if (last != null) {
            log.warn("MemoryModelGateway call exhausted retries: {}", last.getMessage());
        }
        return null;
    }

    /** 简易超时保护 —— 线程 interrupt + 超时阈值。 */
    private <T> T withTimeout(java.util.function.Supplier<T> supplier) {
        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "memory-llm-call");
            t.setDaemon(true);
            return t;
        });
        try {
            java.util.concurrent.Future<T> f = exec.submit(supplier::get);
            return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException tex) {
            log.warn("MemoryModelGateway call timeout after {}ms", timeoutMs);
            return null;
        } catch (Exception ex) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    /** 配置类 debug 视图（测试用）。 */
    @Getter
    public static final class Stats {
        private final boolean available;
        private final boolean fallbackEnabled;
        private final boolean usingMainFallback;
        Stats(boolean available, boolean fallbackEnabled, boolean usingMainFallback) {
            this.available = available;
            this.fallbackEnabled = fallbackEnabled;
            this.usingMainFallback = usingMainFallback;
        }
    }

    public Stats stats() {
        boolean usingMain = memoryModel == null && fallbackToMain && mainModel != null;
        return new Stats(isAvailable(), fallbackToMain, usingMain);
    }

    public Duration timeout() { return Duration.ofMillis(timeoutMs); }
}
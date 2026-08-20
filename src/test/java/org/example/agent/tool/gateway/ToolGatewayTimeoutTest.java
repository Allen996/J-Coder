package org.example.agent.tool.gateway;

import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.signal.ReActLoopSignal;
import org.example.agent.tool.cache.ToolResultStore;
import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.failure.FailureClassifier;
import org.example.agent.tool.failure.RetryPolicy;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.rollback.RollbackSummary;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGatewayTimeoutTest {

    private ExecutorService pool;
    private ToolResultStore noopStore;
    private SideEffectTracker noopTracker;
    private ReActLoopSignal noopSignal;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();
        noopStore = new NoopStore();
        noopTracker = new NoopTracker();
        noopSignal = new NoopSignal();
    }

    @Test
    @DisplayName("工具回调超过 timeoutMs → 返回 brief 含 TIMEOUT;重试 3 次")
    void timeoutTriggersAndRetriesThreeTimes() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback slow = new SlowCallback("slow_tool", attempts, 500);   // 每次 500ms
        // 让 slow_tool descriptor timeoutMs = 100ms
        ToolDescriptor slowDesc = ToolDescriptor.low("slow_tool", "slow").withTimeout(100L);
        ToolDescriptorRegistry reg = new RegistryWithOverride(slowDesc);
        ToolGateway gw = newGateway(slow, reg);

        long start = System.currentTimeMillis();
        String brief = gw.invoke("exec-1", "slow_tool", "{}", 1, noopSignal, List.of());
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(brief);
        assertTrue(brief.contains("TIMEOUT"), "brief 应含 TIMEOUT 错误码; actual=" + brief);
        assertEquals(3, attempts.get(), "TRANSIENT 超时应触发 3 次尝试");
        // 总耗时 ≈ 3 × 100ms = 300ms (实际略多因 backoff 与调度)
        assertTrue(elapsed >= 300, "总耗时应 ≥ 3 次 timeout; elapsed=" + elapsed);
    }

    @Test
    @DisplayName("工具回调在 timeoutMs 内完成 → 正常返回 + 末尾追加 [stored as #<id>]")
    void fastCallSucceedsAndStores() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback fast = new FastCallback("fast_tool", attempts, "fast-result");
        ToolDescriptor fastDesc = ToolDescriptor.low("fast_tool", "fast").withTimeout(2000L);
        ToolDescriptorRegistry reg = new RegistryWithOverride(fastDesc);
        ToolGateway gw = newGateway(fast, reg);

        String result = gw.invoke("exec-1", "fast_tool", "{}", 1, noopSignal, List.of());
        assertEquals(1, attempts.get());
        assertTrue(result.startsWith("fast-result"), "结果前缀; actual=" + result);
        assertTrue(result.contains("[stored as #"), "成功调用应触发外置缓存; actual=" + result);
    }

    @Test
    @DisplayName("descriptor.cacheable=false 的工具不写存储")
    void nonCacheableToolSkipsStorage() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback nc = new FastCallback("nc_tool", attempts, "ok");
        // medium = cacheable=false
        ToolDescriptor desc = ToolDescriptor.medium("nc_tool", true, "nc").withTimeout(2000L);
        ToolDescriptorRegistry reg = new RegistryWithOverride(desc);
        ToolGateway gw = newGateway(nc, reg);

        String result = gw.invoke("exec-1", "nc_tool", "{}", 1, noopSignal, List.of());
        assertTrue(result.equals("ok"), "不应追加 [stored as ...]; actual=" + result);
    }

    // ====================== helpers ======================

    private ToolGateway newGateway(ToolCallback cb, ToolDescriptorRegistry registry) {
        ToolCallbackProvider provider = () -> new ToolCallback[]{cb};
        return new ToolGateway(provider,
                registry,
                new FailureClassifier(),
                new RetryPolicy(),
                noopTracker,
                new CliToolProperties(),
                noopStore,
                pool,
                ToolGateway.AllowAllAuthorizationGate.INSTANCE);
    }

    static class SlowCallback implements ToolCallback {
        private final String name;
        private final AtomicInteger attempts;
        private final long sleepMs;

        SlowCallback(String name, AtomicInteger attempts, long sleepMs) {
            this.name = name;
            this.attempts = attempts;
            this.sleepMs = sleepMs;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("slow").inputSchema("{}").build();
        }

        @Override
        public String call(String input) {
            attempts.incrementAndGet();
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "should-not-reach";
        }
    }

    static class FastCallback implements ToolCallback {
        private final String name;
        private final AtomicInteger attempts;
        private final String result;

        FastCallback(String name, AtomicInteger attempts, String result) {
            this.name = name;
            this.attempts = attempts;
            this.result = result;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("fast").inputSchema("{}").build();
        }

        @Override
        public String call(String input) {
            attempts.incrementAndGet();
            return result;
        }
    }

    /** 仅注册一个 override descriptor 的注册表,其他工具查不到。 */
    static class RegistryWithOverride extends ToolDescriptorRegistry {
        private final ToolDescriptor override;
        RegistryWithOverride(ToolDescriptor override) {
            this.override = override;
        }
        @Override
        public java.util.Optional<ToolDescriptor> get(String name) {
            if (override.name().equals(name)) return java.util.Optional.of(override);
            return super.get(name);
        }
    }

    static class NoopStore implements ToolResultStore {
        @Override public String save(String t, Map<String, Object> a, String r, String e, ToolDescriptor d) { return "abcdef12"; }
        @Override public RecallResult recall(String id, Integer s, Integer ed, String p) { return new RecallResult.Ok(""); }
        @Override public void invalidateByPath(java.nio.file.Path path) { }
        @Override public List<String> scanIds(String text) { return List.of(); }
        @Override public String metadataHint(String id) { return ""; }
        @Override public void evictIfOverBudget() { }
    }

    static class NoopTracker implements SideEffectTracker {
        @Override public void bind(String e) { }
        @Override public void clear() { }
        @Override public void recordFileChange(String t, String p, byte[] ps) { }
        @Override public RollbackSummary rollbackAll() { return new RollbackSummary(0, List.of()); }
    }

    static class NoopSignal implements ReActLoopSignal {
        @Override public void requestTerminate(org.example.agent.core.reason.FinishReason reason) { }
        @Override public boolean isTerminateRequested() { return false; }
        @Override public org.example.agent.core.reason.FinishReason requestedReason() { return null; }
    }
}

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
import org.example.agent.tool.sandbox.AuthorizationGate;
import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ToolGateway} 在 MEDIUM / HIGH 风险工具前正确触发授权闸。
 *
 * <p>用脚本化闸（{@link ScriptedGate}）模拟用户的三种回答，
 * 确认 invoke() 的放行/拒绝/always 语义。命令闸 / 路径闸不在本测试范围。
 */
class ToolGatewayAuthorizationTest {

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newSingleThreadExecutor();
    }

    @Test
    @DisplayName("LOW 工具 → 不弹授权闸,直接调用 callback")
    void lowRiskSkipsGate() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback cb = new EchoCallback("low_tool", attempts);
        ToolDescriptor desc = ToolDescriptor.low("low_tool", "low-risk").withTimeout(2_000L);
        ScriptedGate gate = new ScriptedGate();   // 不期望任何调用

        ToolGateway gw = newGateway(cb, new RegistryWith(Map.of("low_tool", desc)), gate);
        String result = gw.invoke("exec-1", "low_tool", "{}", 1, noopSignal(), List.of());

        assertEquals(1, attempts.get());
        assertTrue(result.startsWith("ok:low_tool"));
        assertEquals(0, gate.calls, "LOW 工具不应进入闸");
    }

    @Test
    @DisplayName("MEDIUM 工具 + 用户 y → invoke 一次,回调被调")
    void mediumAllowOnce() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback cb = new EchoCallback("write_file", attempts);
        ToolDescriptor desc = ToolDescriptor.medium("write_file", true, "write").withTimeout(2_000L);
        ScriptedGate gate = new ScriptedGate();
        gate.script.put("write_file", AuthorizationGate.Decision.allowOnce());

        ToolGateway gw = newGateway(cb, new RegistryWith(Map.of("write_file", desc)), gate);
        String result = gw.invoke("exec-1", "write_file", "{\"path\":\"/tmp/x\"}", 1, noopSignal(), List.of());

        assertEquals(1, attempts.get());
        assertTrue(result.startsWith("ok:write_file"));
        assertEquals(1, gate.calls);
        assertFalse(gate.isSessionAllowed("write_file"), "y 应只放行一次,不写入 sessionAllowed");
    }

    @Test
    @DisplayName("MEDIUM 工具 + 用户 n → 拒绝,AUTHORIZATION_DENIED,回调未被调")
    void mediumDenyStopsInvocation() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback cb = new EchoCallback("write_file", attempts);
        ToolDescriptor desc = ToolDescriptor.medium("write_file", true, "write").withTimeout(2_000L);
        ScriptedGate gate = new ScriptedGate();
        gate.script.put("write_file", AuthorizationGate.Decision.deny("user said no"));

        ToolGateway gw = newGateway(cb, new RegistryWith(Map.of("write_file", desc)), gate);
        String brief = gw.invoke("exec-1", "write_file", "{}", 1, noopSignal(), List.of());

        assertEquals(0, attempts.get(), "被拒绝时回调不应触发");
        assertNotNull(brief);
        assertTrue(brief.contains("AUTHORIZATION_DENIED"),
                "brief 应含 AUTHORIZATION_DENIED; actual=" + brief);
        assertTrue(brief.contains("user said no"),
                "brief 应含 reason; actual=" + brief);
    }

    @Test
    @DisplayName("MEDIUM 工具 + 用户 always → 第一次问,第二次不问")
    void mediumAlwaysSessionScopes() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback cb = new EchoCallback("git_commit", attempts);
        ToolDescriptor desc = ToolDescriptor.medium("git_commit", true, "commit").withTimeout(2_000L);
        ScriptedGate gate = new ScriptedGate();
        gate.script.put("git_commit", AuthorizationGate.Decision.allowForSession());

        ToolGateway gw = newGateway(cb, new RegistryWith(Map.of("git_commit", desc)), gate);

        gw.invoke("exec-1", "git_commit", "{}", 1, noopSignal(), List.of());
        gw.invoke("exec-2", "git_commit", "{}", 1, noopSignal(), List.of());

        assertEquals(2, attempts.get(), "两次调用都应触达回调");
        assertEquals(1, gate.calls, "always 后第二次不弹闸");
        assertTrue(gate.isSessionAllowed("git_commit"), "always 应写入 sessionAllowed");
    }

    @Test
    @DisplayName("HIGH 工具(run_shell) + 拒绝 → 拒绝,AUTHORIZATION_DENIED")
    void highShellDeny() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback cb = new EchoCallback("run_shell", attempts);
        ToolDescriptor desc = ToolDescriptor.high("run_shell", "shell").withTimeout(2_000L);
        ScriptedGate gate = new ScriptedGate();
        gate.script.put("run_shell", AuthorizationGate.Decision.deny("too risky"));

        ToolGateway gw = newGateway(cb, new RegistryWith(Map.of("run_shell", desc)), gate);
        String brief = gw.invoke("exec-1", "run_shell", "{\"command\":\"rm x\"}", 1, noopSignal(), List.of());

        assertEquals(0, attempts.get());
        assertTrue(brief.contains("AUTHORIZATION_DENIED"));
        assertTrue(brief.contains("too risky"));
    }

    @Test
    @DisplayName("已 sessionAllowed 的工具 → 不弹闸,直接调用")
    void sessionAllowedShortCircuits() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallback cb = new EchoCallback("write_file", attempts);
        ToolDescriptor desc = ToolDescriptor.medium("write_file", true, "write").withTimeout(2_000L);
        ScriptedGate gate = new ScriptedGate();
        gate.rememberSessionAllow("write_file");   // 模拟用户上一轮 always 过

        ToolGateway gw = newGateway(cb, new RegistryWith(Map.of("write_file", desc)), gate);
        String result = gw.invoke("exec-1", "write_file", "{}", 1, noopSignal(), List.of());

        assertEquals(1, attempts.get());
        assertTrue(result.startsWith("ok:write_file"));
        assertEquals(0, gate.calls, "已 sessionAllowed 时不应再弹闸");
    }

    // ====================== helpers ======================

    private ToolGateway newGateway(ToolCallback cb, ToolDescriptorRegistry reg, AuthorizationGate gate) {
        ToolCallbackProvider provider = () -> new ToolCallback[]{cb};
        return new ToolGateway(provider,
                reg,
                new FailureClassifier(),
                new RetryPolicy(),
                new NoopTracker(),
                new CliToolProperties(),
                new NoopStore(),
                pool,
                gate);
    }

    private static ReActLoopSignal noopSignal() {
        return new org.example.agent.core.signal.ReActLoopSignal() {
            @Override public void requestTerminate(org.example.agent.core.reason.FinishReason r) { }
            @Override public boolean isTerminateRequested() { return false; }
            @Override public org.example.agent.core.reason.FinishReason requestedReason() { return null; }
        };
    }

    /** 每次 invoke() 按 toolName 取预设决策；缺失则 fail。 */
    static class ScriptedGate implements AuthorizationGate {
        final Map<String, Decision> script = new LinkedHashMap<>();
        final java.util.Set<String> session = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int calls = 0;

        @Override
        public Decision authorize(ToolDescriptor descriptor, String toolName, Map<String, Object> args) {
            calls++;
            Decision d = script.get(toolName);
            if (d == null) throw new AssertionError("unexpected auth call for " + toolName);
            return d;
        }
        @Override public boolean isSessionAllowed(String toolName) { return session.contains(toolName); }
        @Override public void rememberSessionAllow(String toolName) { if (toolName != null) session.add(toolName); }
    }

    static class EchoCallback implements ToolCallback {
        private final String name;
        private final AtomicInteger attempts;
        EchoCallback(String name, AtomicInteger attempts) {
            this.name = name;
            this.attempts = attempts;
        }
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name).description("echo").inputSchema("{}").build();
        }
        @Override public String call(String input) {
            attempts.incrementAndGet();
            return "ok:" + name;
        }
    }

    /** 仅注册给定 descriptor 的注册表;其他工具查不到(返回空 Optional,等同 unknown tool)。 */
    static class RegistryWith extends ToolDescriptorRegistry {
        private final Map<String, ToolDescriptor> map;
        RegistryWith(Map<String, ToolDescriptor> map) { this.map = map; }
        @Override public java.util.Optional<ToolDescriptor> get(String name) {
            return java.util.Optional.ofNullable(map.get(name));
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
}
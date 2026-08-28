package org.example.agent.intent;

import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmToolGateTest {

    private LlmToolGate gate;
    private ToolDescriptorRegistry registry;
    private IntentAwareToolSet toolSet;
    private final ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);

    @BeforeEach
    void setUp() {
        Map<String, ToolDescriptor> byName = new LinkedHashMap<>();
        byName.put("read_file", ToolDescriptor.low("read_file", "read").withTimeout(2_000L));
        byName.put("write_file", ToolDescriptor.medium("write_file", true, "write").withTimeout(2_000L));
        byName.put("edit_file", ToolDescriptor.medium("edit_file", true, "edit").withTimeout(2_000L));
        byName.put("grep", ToolDescriptor.low("grep", "grep").withTimeout(2_000L));
        byName.put("run_shell", ToolDescriptor.high("run_shell", "shell").withTimeout(2_000L));
        byName.put("list_dir", ToolDescriptor.low("list_dir", "ls").withTimeout(2_000L));
        byName.put("glob_files", ToolDescriptor.low("glob_files", "glob").withTimeout(2_000L));
        byName.put("git_status", ToolDescriptor.low("git_status", "status").withTimeout(2_000L));
        byName.put("git_commit", ToolDescriptor.medium("git_commit", true, "commit").withTimeout(2_000L));
        byName.put("git_diff", ToolDescriptor.low("git_diff", "diff").withTimeout(2_000L));
        byName.put("git_log", ToolDescriptor.low("git_log", "log").withTimeout(2_000L));
        byName.put("git_show", ToolDescriptor.low("git_show", "show").withTimeout(2_000L));
        byName.put("check_command_exists", ToolDescriptor.lowNonCacheable("check_command_exists", "which").withTimeout(2_000L));
        registry = new TestRegistry(byName);
        toolSet = new IntentAwareToolSet(registry);
        gate = new LlmToolGate(registry, toolSet, chatModel, new CliIntentProperties(), "qwen3.7-flash");
    }

    @Test
    @DisplayName("READ_CODE 下用 read_file → ALLOW")
    void readAllow() {
        IntentContext ctx = newContext(IntentLabel.READ_CODE);
        L2ToolGateResult r = gate.evaluate(ctx, "read_file", "{}", 1);
        assertEquals(ToolGateDecision.ALLOW, r.decision());
    }

    @Test
    @DisplayName("READ_CODE 下用 write_file → REWRITE 到 read_file")
    void writeUnderReadRewrites() {
        IntentContext ctx = newContext(IntentLabel.READ_CODE);
        L2ToolGateResult r = gate.evaluate(ctx, "write_file", "{\"path\":\"x\"}", 1);
        assertEquals(ToolGateDecision.REWRITE, r.decision());
        assertEquals("read_file", r.suggestedAlternative());
    }

    @Test
    @DisplayName("CHAT_QA(继承 OFF_TOPIC 语义)下任何工具调用 → BLOCK")
    void offTopicBlocksAllTools() {
        IntentContext ctx = newContext(IntentLabel.CHAT_QA);
        L2ToolGateResult r = gate.evaluate(ctx, "read_file", "{}", 1);
        assertEquals(ToolGateDecision.BLOCK, r.decision());
    }

    @Test
    @DisplayName("未知工具 → BLOCK")
    void unknownToolBlocked() {
        IntentContext ctx = newContext(IntentLabel.READ_CODE);
        L2ToolGateResult r = gate.evaluate(ctx, "nonexistent_tool", "{}", 1);
        assertEquals(ToolGateDecision.BLOCK, r.decision());
    }

    @Test
    @DisplayName("WRITE_PROJECT 早期 step + read_file → ALLOW(先读再写是合理的)")
    void readUnderWriteEarlyAllows() {
        IntentContext ctx = newContext(IntentLabel.WRITE_PROJECT);
        L2ToolGateResult r = gate.evaluate(ctx, "read_file", "{}", 2);
        assertEquals(ToolGateDecision.ALLOW, r.decision());
    }

    @Test
    @DisplayName("WRITE_PROJECT + 后期 step + 纯读(非推荐集合) → WARN(疑似漂移)")
    void readUnderWriteLateWarns() {
        IntentContext ctx = newContext(IntentLabel.WRITE_PROJECT);
        // git_show 是 readonly 但不在 WRITE_PROJECT 推荐集合,late step 触发 WARN
        L2ToolGateResult r = gate.evaluate(ctx, "git_show", "{}", 5);
        assertEquals(ToolGateDecision.WARN, r.decision());
    }

    @Test
    @DisplayName("null intentContext → 默认 ALLOW")
    void nullContextAllows() {
        L2ToolGateResult r = gate.evaluate(null, "read_file", "{}", 1);
        assertEquals(ToolGateDecision.ALLOW, r.decision());
        assertEquals(1.0, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("evaluateDegraded → ALLOW with conf=0.5")
    void degradedAllows() {
        L2ToolGateResult r = gate.evaluateDegraded();
        assertEquals(ToolGateDecision.ALLOW, r.decision());
        assertTrue(r.reason() != null);
    }

    @Test
    @DisplayName("RUN_COMMAND 下用 read_file 工具(非推荐) → WARN 而非 BLOCK")
    void runCmdOutsideRecommendedWarns() {
        IntentContext ctx = newContext(IntentLabel.RUN_COMMAND);
        // list_dir 不在 RUN_COMMAND 推荐集合中,但不在强冲突里 → WARN
        L2ToolGateResult r = gate.evaluate(ctx, "list_dir", "{}", 1);
        assertEquals(ToolGateDecision.WARN, r.decision());
    }

    private IntentContext newContext(IntentLabel label) {
        L1IntentResult r = new L1IntentResult("exec", label, 0.9,
                List.of(new L1IntentResult.Candidate(label, 0.9)),
                Map.of(), List.of(),
                ModelRouteHint.GENERAL, false, null);
        return new IntentContext(r, "qwen3.7-plus");
    }

    static class TestRegistry extends ToolDescriptorRegistry {
        private final Map<String, ToolDescriptor> map;
        TestRegistry(Map<String, ToolDescriptor> map) { this.map = map; }
        @Override public java.util.Optional<ToolDescriptor> get(String name) {
            return java.util.Optional.ofNullable(map.get(name));
        }
        @Override public java.util.Set<String> names() { return map.keySet(); }
    }
}
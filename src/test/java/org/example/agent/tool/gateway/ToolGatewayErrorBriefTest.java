package org.example.agent.tool.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.result.ToolError;
import org.example.agent.tool.result.ToolResult;
import org.example.agent.tool.rollback.RollbackSummary;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolGateway 错误 brief 改动的 smoke test,所有断言都是数值化指标。
 *
 * <p>覆盖两类改动:
 * <ol>
 *   <li>错误 message 多行时 5+15 行非对称采样,中间 "(省略 N 行)" 占位 (sampleHeadTail)</li>
 *   <li>brief 增加 args 段,把刚提交的参数 (Map) 漂亮打印为 JSON 写进去 (renderArgs)</li>
 *   <li>LOGIC 失败时 brief 增加 [logic-rollback] 头 + rolled-back 列表 + action 提示</li>
 * </ol>
 */
class ToolGatewayErrorBriefTest {

    // ======================== sampleHeadTail ========================

    @Test
    @DisplayName("短输入 <= head+tail 时原样返回,不画蛇添足")
    void sampleHeadTail_shortInput_returnsAsIs() {
        String input = "L1\nL2\nL3";
        String out = ToolGateway.sampleHeadTail(input, 5, 15);
        assertEquals(input, out);
    }

    @Test
    @DisplayName("临界值 lines == head+tail 时原样返回")
    void sampleHeadTail_atBoundary_returnsAsIs() {
        String input = IntStream.range(0, 20).mapToObj(i -> "L" + i).collect(Collectors.joining("\n"));
        String out = ToolGateway.sampleHeadTail(input, 5, 15);
        assertEquals(input, out);
    }

    @Test
    @DisplayName("长输入输出恒为 head+1+tail = 21 行")
    void sampleHeadTail_longInput_lineCountIsExactly21() {
        String input = IntStream.range(0, 100).mapToObj(i -> "L" + i).collect(Collectors.joining("\n"));
        String out = ToolGateway.sampleHeadTail(input, 5, 15);
        assertEquals(21L, out.split("\n", -1).length, "5+1(marker)+15=21");
    }

    @Test
    @DisplayName("占位文本含被省略的真实行数,非固定文案")
    void sampleHeadTail_markerReportsActualSkippedCount() {
        String input = IntStream.range(0, 100).mapToObj(i -> "L" + i).collect(Collectors.joining("\n"));
        String out = ToolGateway.sampleHeadTail(input, 5, 15);
        assertTrue(out.contains("省略 80 行"));
    }

    @Test
    @DisplayName("头5行与尾15行内容完全保留,中间任意一行都不能漏出来")
    void sampleHeadTail_preservesHeadAndTailExactly() {
        String input = IntStream.range(0, 100).mapToObj(i -> "L" + i).collect(Collectors.joining("\n"));
        String out = ToolGateway.sampleHeadTail(input, 5, 15);
        assertTrue(out.startsWith("L0\nL1\nL2\nL3\nL4"));
        String tailExpected = IntStream.range(85, 100).mapToObj(i -> "L" + i).collect(Collectors.joining("\n"));
        assertTrue(out.endsWith(tailExpected));
        assertAll(IntStream.range(20, 85).mapToObj(i -> "L" + i).map(line ->
                (org.junit.jupiter.api.function.Executable) () ->
                        assertTrue(!out.contains("\n" + line + "\n"),
                                "中间行 " + line + " 不应出现")));
    }

    @Test
    @DisplayName("空 / null 输入走快速返回,不抛 NPE")
    void sampleHeadTail_nullAndEmpty_safeReturn() {
        assertEquals("", ToolGateway.sampleHeadTail(null, 5, 15));
        assertEquals("", ToolGateway.sampleHeadTail("", 5, 15));
    }

    // ======================== renderArgs ========================

    @Test
    @DisplayName("args 段输出能反向解析回原 Map,键值不丢")
    void renderArgs_roundTrippable() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "/tmp/foo.txt");
        args.put("encoding", "utf-8");
        args.put("maxBytes", 4096);

        String rendered = ToolGateway.renderArgs(args);
        assertNotNull(rendered);
        @SuppressWarnings("unchecked")
        Map<String, Object> round = new ObjectMapper().readValue(rendered, Map.class);
        assertEquals("/tmp/foo.txt", round.get("path"));
        assertEquals("utf-8", round.get("encoding"));
        assertEquals(4096, round.get("maxBytes"));
        assertEquals(args.size(), round.size());
    }

    @Test
    @DisplayName("renderArgs 失败时降级到 toString,不抛异常")
    void renderArgs_unserializable_fallsBackToToString() {
        Object tricky = new Object() {
            @Override public String toString() { return "fallback-toString-marker"; }
        };
        Map<String, Object> args = Map.of("tricky", tricky);
        String out = ToolGateway.renderArgs(args);
        assertNotNull(out);
    }

    // ======================== briefForLlm 集成 ========================

    @Test
    @DisplayName("错误 brief 同时包含 5+15 采样 + args 段")
    void briefForLlm_combinedShape() throws Exception {
        String longMsg = IntStream.range(0, 100).mapToObj(i -> "stack-frame-" + i).collect(Collectors.joining("\n"));
        ToolResult r = ToolResult.error(ToolError.of(
                FailureKind.PARAM, ToolErrorCode.PATH_NOT_FOUND,
                longMsg, "check the path"));

        Map<String, Object> args = Map.of("path", "/nope/missing.txt");
        String brief = NO_ARGS_GATEWAY.briefForLlm(r, args);

        assertTrue(brief.length() > 100);
        assertTrue(brief.contains("省略 80 行"), "长 message 应被采样并注明省略数");
        assertTrue(brief.contains("\nargs: "), "brief 必须含 args 段");
        assertTrue(brief.contains("\"path\""));
        assertTrue(brief.contains("\"/nope/missing.txt\""));
        assertTrue(brief.startsWith("[error: PATH_NOT_FOUND]"));
        assertTrue(brief.contains("\nsuggestion: check the path"));
    }

    @Test
    @DisplayName("LOGIC + rollback 非空时 brief 头部增加 [logic-rollback] + rolled-back 列表 + action 提示")
    void briefForLlm_logicRollback_addsHeadedBlocks() {
        RollbackSummary sum = new RollbackSummary(2, List.of(
                "editFile: src/Foo.java (restored 1024 bytes)",
                "writeFile: src/Bar.java (deleted)"));

        ToolResult r = ToolResult.error(ToolError.of(
                FailureKind.LOGIC, ToolErrorCode.INTERNAL_ERROR,
                "build broke after the edit", "fix and re-plan"));
        Map<String, Object> args = Map.of("path", "src/Foo.java");

        String brief = NO_ARGS_GATEWAY.briefForLlm(r, args, sum);

        assertTrue(brief.startsWith("[logic-rollback] [error: INTERNAL_ERROR]"),
                "头部必须为 [logic-rollback] + [error: ...]; actual=" + brief);
        assertTrue(brief.contains("\nrolled-back:\n  - editFile: src/Foo.java (restored 1024 bytes)"),
                "rolled-back 列表必须保留每条描述");
        assertTrue(brief.contains("\n  - writeFile: src/Bar.java (deleted)"),
                "rolled-back 列表必须按序");
        assertTrue(brief.contains("\naction: re-plan from current file state"),
                "action 提示必须明确要求重新规划");
        assertTrue(brief.contains("\nargs: "));
    }

    @Test
    @DisplayName("LOGIC + rollback 为空时 brief 给(暂无跟踪)提示,但仍要求重新规划")
    void briefForLlm_logicRollback_empty_suggestsCheckGitStatus() {
        RollbackSummary empty = new RollbackSummary(0, List.of());
        ToolResult r = ToolResult.error(ToolError.of(
                FailureKind.LOGIC, ToolErrorCode.SHELL_NONZERO_EXIT,
                "compile failed", "look at logs"));

        String brief = NO_ARGS_GATEWAY.briefForLlm(r, Map.of(), empty);

        // wasEmpty() == true 所以不加 [logic-rollback] 头
        assertTrue(brief.startsWith("[error: SHELL_NONZERO_EXIT]"),
                "rollback 空时不加 [logic-rollback] 头; actual=" + brief);
        assertTrue(brief.contains("\nrolled-back: (none tracked"));
        assertTrue(brief.contains("\naction: re-plan from current state"));
    }

    /**
     * briefForLlm 不读 fields,只需一个 ToolGateway 实例。
     * SideEffectTracker 在 brief 路径上不被消费,挂个 noop。
     */
    private static final ToolGateway NO_ARGS_GATEWAY;
    static {
        org.springframework.ai.tool.ToolCallbackProvider emptyProvider =
                () -> new org.springframework.ai.tool.ToolCallback[0];
        org.example.agent.tool.spi.ToolDescriptorRegistry reg = new org.example.agent.tool.spi.ToolDescriptorRegistry();
        org.example.agent.tool.failure.FailureClassifier cls = new org.example.agent.tool.failure.FailureClassifier();
        org.example.agent.tool.failure.RetryPolicy policy = new org.example.agent.tool.failure.RetryPolicy();
        SideEffectTracker noopTracker = new SideEffectTracker() {
            @Override public void bind(String executionId) { }
            @Override public void clear() { }
            @Override public void recordFileChange(String toolName, String path, byte[] preState) { }
            @Override public RollbackSummary rollbackAll() {
                return new RollbackSummary(0, List.of());
            }
        };
        NO_ARGS_GATEWAY = new ToolGateway(emptyProvider, reg, cls, policy, noopTracker);
    }
}

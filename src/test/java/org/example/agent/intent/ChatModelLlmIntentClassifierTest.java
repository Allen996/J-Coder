package org.example.agent.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三条主路径的覆盖:
 * <ol>
 *   <li>LLM 通过 {@code submit_intent_classification} tool 提交 → 从 arguments 取 JSON</li>
 *   <li>LLM 只回文本(没调工具) → text fallback 解析</li>
 *   <li>任何异常(超时/抛错) → Outcome.fallback</li>
 * </ol>
 *
 * <p>不依赖真实 DashScope —— {@link ChatModel} 全 mock。
 */
class ChatModelLlmIntentClassifierTest {

    private ChatModel chatModel;
    private CliIntentProperties properties;
    private ChatModelLlmIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        properties = new CliIntentProperties();
        classifier = new ChatModelLlmIntentClassifier(chatModel, properties, "qwen3.7-flash");
    }

    @Test
    @DisplayName("LLM 通过 tool_call 提交 → 从 arguments() 解析,不读 text")
    void toolCallPath() {
        String args = """
                {
                  "primary": "WRITE_PROJECT",
                  "confidence": 0.92,
                  "candidates": [
                    {"label": "WRITE_PROJECT", "score": 0.92},
                    {"label": "READ_CODE", "score": 0.4}
                  ],
                  "slots": {"target_file": "Foo.java", "change_type": "edit"},
                  "negative_signals": [],
                  "model_route_hint": "code"
                }
                """;
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_1", "function", ChatModelLlmIntentClassifier.TOOL_NAME, args);
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();
        ChatResponse response = new ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "改一下 Foo.java");

        assertEquals(IntentLabel.WRITE_PROJECT, out.primary());
        assertEquals(0.92, out.confidence(), 0.001);
        assertEquals(ModelRouteHint.CODE, out.modelRouteHint());
        assertEquals(2, out.candidates().size());
        assertEquals("Foo.java", out.slots().get("target_file"));
        assertFalse(out.degraded(), "structured tool path should not be degraded");
        assertEquals(null, out.reason());
    }

    @Test
    @DisplayName("tool_call.arguments() 不是合法 JSON → fallback 报错")
    void toolCallBadJson() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_1", "function", ChatModelLlmIntentClassifier.TOOL_NAME,
                "this is not json");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();
        ChatResponse response = new ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "改一下");

        assertTrue(out.degraded(), "bad tool args should be degraded");
        assertNotNull(out.reason());
        assertTrue(out.reason().startsWith("tool args json"), "reason should explain tool args failure");
    }

    @Test
    @DisplayName("LLM 调了别的工具名(不是 submit_intent_classification) → 当成无工具,走文本路径")
    void wrongToolNameFallsBackToText() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "call_1", "function", "some_other_tool", "{}");
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();
        ChatResponse response = new ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "改一下");
        assertTrue(out.degraded(), "no matching tool → degrade");
    }

    @Test
    @DisplayName("LLM 只回文本(没调工具) → text fallback 解析")
    void textFallbackPath() {
        String text = """
                ```json
                {
                  "primary": "READ_CODE",
                  "confidence": 0.88,
                  "candidates": [{"label": "READ_CODE", "score": 0.88}],
                  "slots": {"target": "Foo.java"},
                  "negative_signals": [],
                  "model_route_hint": "light"
                }
                ```
                """;
        AssistantMessage assistant = new AssistantMessage(text);
        ChatResponse response = new ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "看看 Foo.java");

        assertEquals(IntentLabel.READ_CODE, out.primary());
        assertEquals(0.88, out.confidence(), 0.001);
        assertEquals(ModelRouteHint.LIGHT, out.modelRouteHint());
        assertFalse(out.degraded());
    }

    @Test
    @DisplayName("文本非 JSON 且未调工具 → fallback")
    void textFallbackGarbage() {
        AssistantMessage assistant = new AssistantMessage("I think this is a write task.");
        ChatResponse response = new ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "改一下");

        assertTrue(out.degraded());
        assertNotNull(out.reason());
    }

    @Test
    @DisplayName("ChatModel.call 抛异常 → fallback 不抛给上层")
    void exceptionPath() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("network down"));

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "改一下");
        assertTrue(out.degraded());
        assertTrue(out.reason().contains("network down"), "reason should propagate cause, got: " + out.reason());
    }

    @Test
    @DisplayName("ChatModel.call 返回 null → fallback")
    void nullResponsePath() {
        when(chatModel.call(any(Prompt.class))).thenReturn(null);

        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "改一下");
        assertTrue(out.degraded());
    }

    @Test
    @DisplayName("空输入 → 直接 fallback,不走 ChatModel")
    void emptyInput() {
        LlmIntentClassifier.Outcome out = classifier.classify("exec-1", "");
        assertTrue(out.degraded());
        assertEquals("empty input", out.reason());
        verify(chatModel, times(0)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Prompt 里 ToolCallback 一定带上 submit_intent_classification(强制结构化)")
    void toolCallbackWired() {
        // 准备一次 tool_call 响应,让 ChatModel 被调用一次
        String args = "{\"primary\":\"CHAT_QA\",\"confidence\":0.5,\"model_route_hint\":\"general\"}";
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "c1", "function", ChatModelLlmIntentClassifier.TOOL_NAME, args);
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(tc))
                .build();
        ChatResponse response = new ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(assistant)));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        classifier.classify("exec-1", "你好");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(1)).call(captor.capture());
        Prompt p = captor.getValue();
        Object opts = p.getOptions();
        assertNotNull(opts);
        // 直接用反射抓 toolCallbacks 字段(避免引入额外 getter 依赖)
        try {
            java.lang.reflect.Field f = opts.getClass().getDeclaredField("toolCallbacks");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<org.springframework.ai.tool.ToolCallback> tcs =
                    (List<org.springframework.ai.tool.ToolCallback>) f.get(opts);
            assertNotNull(tcs);
            assertEquals(1, tcs.size(), "exactly one tool callback should be registered");
            ToolDefinition def = tcs.get(0).getToolDefinition();
            assertEquals(ChatModelLlmIntentClassifier.TOOL_NAME, def.name());
            assertNotNull(def.inputSchema(), "input schema must be present");
            assertTrue(def.inputSchema().contains("\"primary\""), "schema should describe primary field");
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("could not inspect toolCallbacks via reflection", ex);
        }
    }
}

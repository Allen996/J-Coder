package org.example.agent.intent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用 Spring AI 的 ChatModel 直接做意图分类。
 *
 * <p>复用现有 chatModel bean,通过 DashScopeChatOptions.withModel 覆盖模型名,
 * 走"轻量档"模型。prompt 控制在 500 token 以内。
 *
 * <p><b>结构化输出策略</b>:不依赖 prompt 约束 LLM 输出 JSON,而是注册一个
 * {@code submit_intent_classification} 工具,要求 LLM 必须以
 * {@code tool_call} 形式提交结果 —— 这样 {@code ToolCall.arguments()} 是
 * 平台保证的 JSON,无需任何文本解析即可拿到结构化数据。
 *
 * <p>工具不会真正被执行:option 设了 {@code internalToolExecutionEnabled(false)},
 * 同时 ToolCallback 的 {@code call()} 返回固定响应,ReActLoop 也根本看不到这个
 * tool callback(它只走 chatModel 那条路径)。
 *
 * <p>降级链:
 * <ol>
 *   <li>LLM 调工具 → 直接解析 arguments,无文本解析</li>
 *   <li>LLM 没调工具(返回了纯文本) → 走文本解析兜底(旧的 parse 路径)</li>
 *   <li>任何异常/超时 → Outcome.fallback(reason)</li>
 * </ol>
 */
@Component
public class ChatModelLlmIntentClassifier implements LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(ChatModelLlmIntentClassifier.class);

    /** 工具名:LLM 必须以这个 name 提交 tool_call。 */
    static final String TOOL_NAME = "submit_intent_classification";

    /**
     * JSON Schema —— 平台(DashScope)会用它生成 tool 定义,
     * LLM 按 schema 填字段后以 tool_call 形式返回。
     *
     * <p>字段含义:
     * <ul>
     *   <li>primary —— 6 个 IntentLabel 之一</li>
     *   <li>confidence —— [0,1]</li>
     *   <li>candidates —— 至多 3 个 {label, score}</li>
     *   <li>slots —— 填不出的 key 可省略,留空对象表示无</li>
     *   <li>negative_signals —— 反向信号(冲突词、风险词)列表</li>
     *   <li>model_route_hint —— light / code / general</li>
     * </ul>
     */
    static final String SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "primary": {
                  "type": "string",
                  "enum": ["READ_CODE", "WRITE_PROJECT", "RUN_COMMAND", "CHAT_QA", "PLANNING", "OFF_TOPIC"],
                  "description": "主意图标签"
                },
                "confidence": {
                  "type": "number",
                  "minimum": 0,
                  "maximum": 1,
                  "description": "主意图置信度,0~1"
                },
                "candidates": {
                  "type": "array",
                  "maxItems": 3,
                  "items": {
                    "type": "object",
                    "properties": {
                      "label": {"type": "string", "enum": ["READ_CODE", "WRITE_PROJECT", "RUN_COMMAND", "CHAT_QA", "PLANNING", "OFF_TOPIC"]},
                      "score": {"type": "number", "minimum": 0, "maximum": 1}
                    },
                    "required": ["label", "score"]
                  }
                },
                "slots": {
                  "type": "object",
                  "description": "意图相关槽位,如 target_file / change_type / target_dir 等;填不出可省略"
                },
                "negative_signals": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "反向信号词或冲突描述"
                },
                "model_route_hint": {
                  "type": "string",
                  "enum": ["light", "code", "general"],
                  "description": "推荐使用的模型档位"
                }
              },
              "required": ["primary", "confidence", "model_route_hint"]
            }
            """;

    /**
     * 工具元数据:LLM 看到的"工具说明"。
     * 强调"只通过工具提交结果,不要输出自然语言" —— 这是 prompt 层面
     * 的冗余约束,真正的强制性来自 tool calling 协议本身。
     */
    private static final String TOOL_DESCRIPTION = """
            提交一次意图分类结果。**必须**通过此工具的 arguments 字段提交 JSON,
            不要在 assistant 文本中输出任何自然语言 —— 文本部分会被忽略。
            """;

    private final ChatModel chatModel;
    private final CliIntentProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String cheapModel;
    private final ToolCallback submitIntentTool;

    public ChatModelLlmIntentClassifier(ChatModel chatModel,
                                        CliIntentProperties properties,
                                        @Value("${cli.intent.model-routing.light:qwen3.7-flash}") String cheapModel) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.cheapModel = cheapModel;
        this.submitIntentTool = buildSubmitIntentTool();
    }

    @Override
    public Outcome classify(String executionId, String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return Outcome.fallback("empty input");
        }
        long timeoutMs = properties.l1().timeoutMs();
        Prompt prompt = buildPrompt(userInput);
        ChatResponse response;
        try {
            CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> chatModel.call(prompt));
            response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("executionId={} L1 classify timeout after {}ms", executionId, timeoutMs);
            return Outcome.fallback("timeout after " + timeoutMs + "ms");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Outcome.fallback("interrupted");
        } catch (ExecutionException ee) {
            log.warn("executionId={} L1 classify failed: {}", executionId, ee.getMessage());
            return Outcome.fallback("execution: " + (ee.getCause() == null ? ee : ee.getCause().getMessage()));
        } catch (RuntimeException re) {
            log.warn("executionId={} L1 classify runtime error: {}", executionId, re.toString());
            return Outcome.fallback("runtime: " + re.getMessage());
        }

        if (response == null || response.getResult() == null) {
            return Outcome.fallback("null response");
        }
        AssistantMessage out = response.getResult().getOutput();
        if (out == null) {
            return Outcome.fallback("null assistant message");
        }

        // 路径 1(主路径):LLM 通过 tool_call 提交了结构化结果
        Outcome fromTool = extractFromToolCall(out, executionId);
        if (fromTool != null) {
            return fromTool;
        }

        // 路径 2(降级):LLM 没调工具,只返回了文本 —— 走老 parse 路径
        String text = out.getText();
        if (text != null && !text.isBlank()) {
            Outcome fromText = parse(text);
            if (fromText != null) return fromText;
        }

        // 路径 3:文本 + 工具都没有,或工具 args 解析失败
        return Outcome.fallback("no tool call and text was empty/unparseable");
    }

    /**
     * 从 AssistantMessage 中抽取 LLM 调 {@link #TOOL_NAME} 的结果。
     *
     * @return 解析成功返回 Outcome;没调工具 / 工具名不对 / args 解析失败都返回 null,
     *         调用方据此决定是否走文本兜底路径。
     */
    private Outcome extractFromToolCall(AssistantMessage out, String executionId) {
        if (!out.hasToolCalls()) return null;
        List<AssistantMessage.ToolCall> calls = out.getToolCalls();
        AssistantMessage.ToolCall target = null;
        for (AssistantMessage.ToolCall tc : calls) {
            if (TOOL_NAME.equals(tc.name())) {
                target = tc;
                break;
            }
        }
        if (target == null) return null;

        String args = target.arguments();
        if (args == null || args.isBlank()) {
            log.warn("executionId={} L1 tool call had empty arguments", executionId);
            return Outcome.fallback("tool call returned empty arguments");
        }
        try {
            return parseArgsJson(args);
        } catch (Exception ex) {
            log.warn("executionId={} L1 tool args JSON parse failed: {}", executionId, ex.toString());
            return Outcome.fallback("tool args json: " + ex.getMessage());
        }
    }

    private Prompt buildPrompt(String userInput) {
        String sys = "你是 J-Coder 的意图分类器。\n" +
                "对用户输入做意图分类,然后通过调用 submit_intent_classification 工具提交结果。\n" +
                "不要在 assistant 文本中输出任何自然语言 —— 所有内容都应通过工具的 arguments 提交。\n" +
                "工具调用一次即结束。";
        List<Message> msgs = List.of(
                new SystemMessage(sys),
                new UserMessage(truncate(userInput, 1000))
        );
        com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions opts =
                com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withModel(cheapModel)
                        .withTemperature(0.0)
                        .withMultiModel(true)
                        .toolCallbacks(List.of(submitIntentTool))
                        .withInternalToolExecutionEnabled(Boolean.FALSE)
                        .build();
        return new Prompt(msgs, opts);
    }

    /**
     * 解析 tool_call.arguments() 返回的 JSON。
     * 与 {@link #parse(String)} 共享同样的字段语义,但入口是已经保证是 JSON 的字符串。
     */
    private Outcome parseArgsJson(String argsJson) throws Exception {
        Map<String, Object> obj = mapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {});
        return buildOutcomeFromMap(obj);
    }

    /**
     * 文本兜底解析:LLM 没调工具,只回了自然语言文本时尝试从中抠出 JSON。
     */
    private Outcome parse(String text) {
        String json = stripCodeFence(text);
        Map<String, Object> obj;
        try {
            obj = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return null;  // 不是 JSON —— 让调用方 fallback
        }
        try {
            return buildOutcomeFromMap(obj);
        } catch (Exception ex) {
            return null;
        }
    }

    /** 公共解析逻辑:把 Map 转成 Outcome。字段缺失/类型不对会通过 fallback 兜底。 */
    private Outcome buildOutcomeFromMap(Map<String, Object> obj) {
        IntentLabel primary = IntentLabel.parseLoose(String.valueOf(obj.get("primary")));
        if (primary == null) {
            return Outcome.fallback("unknown primary label: " + obj.get("primary"));
        }
        double conf = clamp(toDouble(obj.get("confidence"), 0.5));
        List<L1IntentResult.Candidate> candidates = new ArrayList<>();
        Object rawCandidates = obj.get("candidates");
        if (rawCandidates instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    IntentLabel lab = IntentLabel.parseLoose(String.valueOf(m.get("label")));
                    if (lab != null) candidates.add(new L1IntentResult.Candidate(lab, clamp(toDouble(m.get("score"), 0.0))));
                }
            }
        }
        Map<String, Object> slots = obj.get("slots") instanceof Map<?, ?> m
                ? new java.util.HashMap<>((Map<String, Object>) m)
                : Map.of();
        List<String> negatives = new ArrayList<>();
        if (obj.get("negative_signals") instanceof List<?> list) {
            for (Object o : list) if (o != null) negatives.add(String.valueOf(o));
        }
        ModelRouteHint hint = ModelRouteHint.parseLoose(String.valueOf(obj.get("model_route_hint")));
        if (hint == null) hint = ModelRouteHint.GENERAL;
        return new Outcome(primary, conf, candidates, slots, negatives, hint, false, null);
    }

    /**
     * 构造一个永远不会被执行的 ToolCallback。
     * {@code internalToolExecutionEnabled(false)} 已经保证框架不会调它,
     * 但 call() 还是要给一个安全实现,防止某些路径(测试/未来重构)真的调到这里。
     */
    private static ToolCallback buildSubmitIntentTool() {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(SCHEMA_JSON)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }
            @Override
            public String call(String input) {
                // 设计上不会被调用:本分类器不通过 ToolGateway 走真实执行链路,
                // 框架侧 internalToolExecutionEnabled(false) 也不会触发。
                return "{\"acknowledged\":true}";
            }
        };
    }

    private static String stripCodeFence(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private static double toDouble(Object o, double fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception ex) { return fallback; }
    }

    private static double clamp(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}

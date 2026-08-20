package org.example.agent.intent;

import org.example.agent.tool.spi.ToolDescriptor;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * L2 工具语义门控。设计稿 §4。
 *
 * <p>逐调用判别:输入当前 L1 意图 + L2 历史 + 本步工具定义,
 * 给出 ALLOW / WARN / BLOCK / REWRITE 决策。
 *
 * <p>首期实现策略:
 * <ol>
 *   <li>工具不在 ToolDescriptorRegistry 中 → BLOCK(未知工具,沙箱层也会再拦一次)</li>
 *   <li>工具属于 L1 推荐集合 → ALLOW</li>
 *   <li>工具与 L1 强冲突(读意图下用写工具 / 写意图下只用只读工具) → WARN 或 BLOCK</li>
 *   <li>工具语义不对(模型想 edit_file 但意图是读) → REWRITE</li>
 * </ol>
 *
 * <p>解析失败 / 超时默认 ALLOW —— 设计稿 §4.1:"宁可漏判不要误拦"。
 */
@Component
public class LlmToolGate {

    private final ToolDescriptorRegistry registry;
    private final IntentAwareToolSet toolSet;
    private final ChatModel chatModel;
    private final CliIntentProperties properties;
    private final String cheapModel;

    public LlmToolGate(ToolDescriptorRegistry registry,
                       IntentAwareToolSet toolSet,
                       ChatModel chatModel,
                       CliIntentProperties properties,
                       @Value("${cli.intent.model-routing.light:qwen3.7-flash}") String cheapModel) {
        this.registry = registry;
        this.toolSet = toolSet;
        this.chatModel = chatModel;
        this.properties = properties;
        this.cheapModel = cheapModel;
    }

    /**
     * 对单个 ToolCall 做语义门控。
     *
     * @param l1         当前 L1 上下文(可空 —— 空时按 ALLOW 处理)
     * @param toolCallName 模型这一步想调的工具名
     * @param toolArgsJson 工具调用参数(JSON 字符串)
     * @param step       当前 step 计数(供日志)
     */
    public L2ToolGateResult evaluate(IntentContext l1, String toolCallName, String toolArgsJson, int step) {
        if (l1 == null) return L2ToolGateResult.allow(1.0, "no intent context");
        if (toolCallName == null) return L2ToolGateResult.block(0.0, "null tool name");

        // 1) 未知工具:硬拦(design §7 沙箱层兜底,但提前拦可减少噪声)
        if (registry.get(toolCallName).isEmpty()) {
            return L2ToolGateResult.block(0.0, "unknown tool: " + toolCallName);
        }
        ToolDescriptor desc = registry.get(toolCallName).get();

        // 2) 工具是否属于本意图推荐集合
        boolean recommended = toolSet.isRecommendedFor(l1.primaryLabel(), toolCallName);
        boolean writeUnderRead = isWriteTool(desc) && l1.primaryLabel() == IntentLabel.READ_CODE;
        boolean readUnderWrite = isPureReadOnly(desc) && l1.primaryLabel() == IntentLabel.WRITE_PROJECT
                && step >= 3; // 写意图前两步可读(先看再改),后面纯读视为漂移

        if (recommended) {
            return L2ToolGateResult.allow(0.95, "tool in recommended set for " + l1.primaryLabel());
        }

        // 3) 强冲突:读意图下用写工具 → 直接改写为对应读工具
        if (writeUnderRead) {
            String alt = pickReadAlternative(l1.primaryLabel(), toolCallName);
            if (alt != null) {
                return L2ToolGateResult.rewrite(0.90,
                        "write tool under READ_CODE → rewrite to " + alt, alt);
            }
            return L2ToolGateResult.block(0.85, "write tool under READ_CODE");
        }

        // 4) 写意图下持续纯读 → WARN
        if (readUnderWrite) {
            return L2ToolGateResult.warn(0.7,
                    "read-only tool under WRITE_PROJECT step=" + step + " (drift?)", null);
        }

        // 5) 非推荐集合但不属于强冲突:放进候选集,标 WARN
        if (l1.primaryLabel() == IntentLabel.OFF_TOPIC || l1.primaryLabel() == IntentLabel.CHAT_QA) {
            // 离主题/纯问答场景下不应调用任何工具,降级为 BLOCK
            return L2ToolGateResult.block(0.7, "tool not allowed under " + l1.primaryLabel());
        }
        return L2ToolGateResult.warn(0.65,
                "tool outside recommended set for " + l1.primaryLabel(), null);
    }

    /**
     * 失败兜底:解析失败/超时 → 默认 ALLOW。
     */
    public L2ToolGateResult evaluateDegraded() {
        return L2ToolGateResult.allow(0.5, "gate degraded");
    }

    private boolean isWriteTool(ToolDescriptor d) {
        if (d == null) return false;
        return d.risk() != null && (d.risk().name().equals("MEDIUM") || d.risk().name().equals("HIGH"));
    }

    private boolean isPureReadOnly(ToolDescriptor d) {
        if (d == null) return false;
        return d.readonly() && d.risk() != null && d.risk().name().equals("LOW");
    }

    private String pickReadAlternative(IntentLabel label, String toolCallName) {
        List<String> candidates = toolSet.recommendedFor(label);
        // 简单优先(有序):read_file > grep > list_dir > glob_files
        List<String> priority = java.util.Arrays.asList("read_file", "grep", "list_dir", "glob_files");
        for (String p : priority) {
            if (candidates.contains(p) && !p.equals(toolCallName)) return p;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
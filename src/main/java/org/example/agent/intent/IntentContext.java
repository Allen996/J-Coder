package org.example.agent.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 挂在 {@link org.example.agent.core.loop.ReActLoop} 实例上的可读上下文。
 *
 * <p>贯穿整个 loop,给 L2 工具门控 / 模型路由 / 日志观测者消费。
 * 设计稿 §2:"三个数据对象之一"。
 *
 * <p>首期 sticky 策略:每个 ReActLoop 实例只跑一次 L1(在 subscribe 入口),
 * 后续步骤 L1 不会再触发。如要做"第 N 轮重跑 L1",需要 L2 在
 * {@link #recentToolHistory} 中观察漂移信号,由 IntentGate 主动决策重跑。
 */
public final class IntentContext {

    private final L1IntentResult l1;
    private final List<ToolHistoryEntry> toolHistory = new ArrayList<>();
    private volatile String resolvedModel;
    private volatile boolean sticky = true;
    // 第三阶段:StrongPatternClassifier 触发时记录,供 eval / 日志观测
    private volatile List<String> strongPatternTypes = List.of();
    private volatile String clarifyText = null;
    // 第三阶段:IntentGate 决策后的 tier(可能与 l1.confidence 算出的 tier 不同 —
    // 强 pattern 触发时强制 CLARIFY 但 conf 不变)
    private volatile L1IntentResult.Tier decidedTier;

    public IntentContext(L1IntentResult l1, String resolvedModel) {
        this.l1 = l1;
        this.resolvedModel = resolvedModel;
        this.decidedTier = null; // null 表示"按 l1.confidence 算"
    }

    /**
     * 第三阶段:IntentGate 在三档决策后,把最终 tier 存进来。
     * 读取时 {@link #tier()} 会优先返回这里存的值;如果未存(null),回落到
     * {@link L1IntentResult#tier()} 的旧行为。
     */
    public void setDecidedTier(L1IntentResult.Tier tier) {
        this.decidedTier = tier;
    }

    /** IntentGate 决策后的 tier;若未设置则按 l1.confidence 算。 */
    public L1IntentResult.Tier tier() {
        return decidedTier != null ? decidedTier : l1.tier();
    }

    /**
     * 第三阶段:把"被反问的强 pattern 类型"和"反问文本"挂到 ctx 上,
     * 供 per-row eval jsonl 与 LogSink 消费。{@code types} 为 null / 空表示未触发反问。
     */
    public void attachStrongPattern(List<String> types, String clarifyText) {
        this.strongPatternTypes = types == null ? List.of() : List.copyOf(types);
        this.clarifyText = clarifyText;
    }

    /** 命中的强 pattern 类型(可空集合表示未触发)。 */
    public List<String> strongPatternTypes() {
        return strongPatternTypes;
    }

    /** 反问文本(可能为 null —— 未触发或 suppress 下不返回)。 */
    public String clarifyText() {
        return clarifyText;
    }

    /** 便捷判断:本次 L1 是否触发了强 pattern 反问。 */
    public boolean isClarifiedByStrongPattern() {
        return clarifyText != null && !clarifyText.isBlank();
    }

    public L1IntentResult l1() {
        return l1;
    }

    public IntentLabel primaryLabel() {
        return l1 == null ? IntentLabel.CHAT_QA : l1.primary();
    }

    public ModelRouteHint routeHint() {
        return l1 == null ? ModelRouteHint.GENERAL : l1.modelRouteHint();
    }

    public String resolvedModel() {
        return resolvedModel;
    }

    public void overrideModel(String name) {
        if (name != null && !name.isBlank()) {
            this.resolvedModel = name.trim();
        }
    }

    public boolean isSticky() {
        return sticky;
    }

    public void setSticky(boolean sticky) {
        this.sticky = sticky;
    }

    /** 追加一条工具调用结果(L2 决策后写)。 */
    public void recordTool(ToolHistoryEntry entry) {
        if (entry != null) toolHistory.add(entry);
    }

    public List<ToolHistoryEntry> recentToolHistory() {
        return Collections.unmodifiableList(toolHistory);
    }

    /** 最近 N 步实际调用的工具名(成功 / 失败 / 被拦 一并记录)。 */
    public List<String> recentToolNames(int window) {
        int n = Math.min(window, toolHistory.size());
        List<String> out = new ArrayList<>(n);
        for (int i = toolHistory.size() - n; i < toolHistory.size(); i++) {
            out.add(toolHistory.get(i).toolName());
        }
        return out;
    }

    /** L2 门控用到的工具调用历史条目。 */
    public record ToolHistoryEntry(String toolName, String decision, double confidence) {}
}
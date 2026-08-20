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

    public IntentContext(L1IntentResult l1, String resolvedModel) {
        this.l1 = l1;
        this.resolvedModel = resolvedModel;
    }

    public L1IntentResult l1() {
        return l1;
    }

    public IntentLabel primaryLabel() {
        return l1 == null ? IntentLabel.OFF_TOPIC : l1.primary();
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
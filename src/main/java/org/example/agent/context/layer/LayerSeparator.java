package org.example.agent.context.layer;

/**
 * 层与层之间的视觉分隔符（part3.md §6.4）。
 *
 * <p>约定：渲染 Static + Dynamic 两层时，在两层之间插一行 Markdown 水平线，
 * 让模型在长上下文中能稳定识别"系统设定"与"对话内容"的分界点。
 *
 * <p>分隔符不携带语义，仅作视觉提示。
 */
public final class LayerSeparator {

    public static final String MARKER = "---";
    public static final String DYNAMIC_START_TAG = "[DYNAMIC_START]";

    private LayerSeparator() { }

    /** 渲染时插入到 Static 层末尾与 Dynamic 层开头的一行。 */
    public static String render() {
        return MARKER;
    }
}

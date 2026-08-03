package org.example.agent.tool.spi;

/**
 * 工具风险等级。
 *
 * <p>对应 5.1 表里的"需授权"列：
 * <ul>
 *   <li>{@link #LOW} —— 不需授权（read_file / list_dir / grep / git_status / ...）</li>
 *   <li>{@link #MEDIUM} —— 需用户授权（write_file / edit_file / git_commit / ...）</li>
 *   <li>{@link #HIGH} —— 需用户授权 + 沙箱强校验（run_shell）</li>
 * </ul>
 *
 * <p>授权 UI 在 v1 暂未接，{@link #MEDIUM} / {@link #HIGH} 工具默认走"自动拒绝"直到 v2 接入。
 */
public enum ToolRisk {
    LOW,
    MEDIUM,
    HIGH
}

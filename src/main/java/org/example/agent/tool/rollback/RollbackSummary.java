package org.example.agent.tool.rollback;

import java.util.List;

/**
 * 回滚执行摘要。{@link SideEffectTracker#rollbackAll()} 的返回值,会同时被塞进
 * 给 LLM 的 brief 和 {@code RollbackEvent}。
 *
 * @param rolledCount   成功恢复的条目数
 * @param rolledTargets 每条记录的执行描述,例如 {@code "editFile: src/Foo.java (restored 1024 bytes)"}
 *                      失败的会带 {@code "(FAILED: <reason>)"},便于 LLM 知道哪些没回滚到
 */
public record RollbackSummary(int rolledCount, List<String> rolledTargets) {

    public boolean wasEmpty() {
        return rolledTargets == null || rolledTargets.isEmpty();
    }

    /** 渲染成多行文本,适合直接塞进 brief 的 {@code rolled-back:} 段。 */
    public String render() {
        if (wasEmpty()) return "(none tracked)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rolledTargets.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append("  - ").append(rolledTargets.get(i));
        }
        return sb.toString();
    }
}

package org.example.agent.core.task;

/**
 * SubTask 类型（part5 §8.5）。
 *
 * <ul>
 *   <li>IMPLEMENT: 代码实现类</li>
 *   <li>ANALYZE: 纯分析 / 调查类</li>
 *   <li>REFACTOR: 重构类</li>
 *   <li>VERIFY: 强制验证（不可 SKIPPED、不可被 LLM 显式 COMPLETED 跳过）</li>
 *   <li>FIX: VERIFY 失败后由 orchestrator 自动插入的修复子任务</li>
 * </ul>
 */
public enum SubTaskType {
    IMPLEMENT,
    ANALYZE,
    REFACTOR,
    VERIFY,
    FIX
}

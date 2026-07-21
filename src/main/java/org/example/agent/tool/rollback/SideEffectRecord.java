package org.example.agent.tool.rollback;

/**
 * 单条副作用记录。{@code preState == null} 表示记录前文件不存在,rollback 时执行删除。
 *
 * @param toolName 触发该副作用的工具名(便于摘要里诊断)
 * @param path     文件路径(与调用方传入的一致,绝对或项目相对)
 * @param preState 变更前字节;{@code null} 表示 pre-state 是"不存在"
 */
public record SideEffectRecord(String toolName, String path, byte[] preState) {
}

package org.example.agent.tool.rollback;

import java.util.List;

/**
 * 工具副作用追踪。
 *
 * <p>v1 范围: in-memory, 单任务内有效。{@link InMemorySideEffectTracker} 用 ThreadLocal 把
 * "当前任务 executionId" 绑到当前线程,FileTools 在写入前调 {@link #recordFileChange}
 * 记录 pre-state;{@code ToolGateway} 在识别到 LOGIC 失败且目标可逆时调
 * {@link #rollbackAll} 撤销已写入的文件,再把"撤销清单"塞进 brief 告诉 LLM 重新规划。
 *
 * <p>v2 接 SQLite 持久化(见 part2.md §5.6.5),本接口签名不变,只换实现。
 */
public interface SideEffectTracker {

    /**
     * 在一次 task 开始时绑定当前 executionId 到调用线程。
     * 必须由 {@code ReActLoop.subscribe()} 在进入循环前同步调用。
     */
    void bind(String executionId);

    /**
     * task 结束时清理绑定与会话栈。失败时(rollback 已执行)也走这里 —— rollback 主动调,
     * 然后 cleanup 收尾。
     */
    void clear();

    /**
     * 把"工具即将修改这个文件"这件事记录下来,保存 pre-state 字节。
     * 必须先于实际写入调用,以便能在失败时还原。
     *
     * @param toolName 工具名(便于 debug 日志定位)
     * @param path     项目内路径,与 {@code tool} 看到的入参一致
     * @param preState 修改前的字节,文件不存在时为 {@code null}
     */
    void recordFileChange(String toolName, String path, byte[] preState);

    /**
     * 反向执行栈内所有记录,把目标文件还原到 pre-state。
     * 失败的部分以 "FAILED: <msg>" 形式写进 summary,不抛异常。
     *
     * @return 撤销摘要,包括成功条数与每条记录的执行描述
     */
    RollbackSummary rollbackAll();
}

package org.example.agent.core.signal;

import org.example.agent.core.reason.FinishReason;

/**
 * 单次执行的协作式控制面（control plane）。
 *
 * 观察者链拿到的不只是只读 event，还有一份 ReActLoopSignal 用于在越界时
 * 主动请求终止。AgentRuntimeImpl 在每个 step 之间读取 signal 状态决定是否
 * 立即发出 FINISH。多次调用 requestTerminate 以第一次为准。
 *
 * 与 AgentHandle.cancelNow 的区别：
 *  - AgentHandle.cancelNow 是用户级的语义「我不关心了」，通常在外面点断开 / 取消按钮
 *  - ReActLoopSignal.requestTerminate 是 runtime 内部观察者发起的防御性终结
 */
public interface ReActLoopSignal {

    /** 请求终止。后续 step 不再执行，立刻 emit FINISH(reason)。 */
    void requestTerminate(FinishReason reason);

    /** 是否已经被请求终止。 */
    boolean isTerminateRequested();

    /** 已经请求的终止原因，未触发时返回 null。 */
    FinishReason requestedReason();
}

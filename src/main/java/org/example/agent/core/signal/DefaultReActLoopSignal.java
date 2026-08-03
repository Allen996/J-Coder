package org.example.agent.core.signal;

import lombok.Getter;
import org.example.agent.core.reason.FinishReason;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReActLoopSignal 的默认实现。AgentRuntimeImpl 在每次 execute / stream 调用时
 * 新建一个实例，绑定到具体 executionId。
 */
@Getter
public class DefaultReActLoopSignal implements ReActLoopSignal {

    private final AtomicBoolean terminateRequested = new AtomicBoolean(false);
    private final AtomicReference<FinishReason> reason = new AtomicReference<>();

    @Override
    public void requestTerminate(FinishReason reason) {
        terminateRequested.set(true);
        this.reason.compareAndSet(null, reason);
    }

    @Override
    public boolean isTerminateRequested() {
        return terminateRequested.get();
    }

    @Override
    public FinishReason requestedReason() {
        return reason.get();
    }
}

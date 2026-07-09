package org.example.agent.core.registry;

import lombok.RequiredArgsConstructor;
import org.example.agent.core.handle.AgentHandle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 executionId 映射到当前在跑的 AgentHandle。
 *
 * AgentRuntime.cancel(executionId) 通过本注册表调用 handle.cancelNow()，
 * AgentRuntimeImpl 在内部 subscriber chain 通过 FluxSink 实现协作式中断。
 *
 * 4.1 阶段使用进程内 ConcurrentHashMap，4.4/4.5 阶段可换成 Redis 共享（多副本部署）。
 */
@RequiredArgsConstructor
public class ExecutionRegistry {

    private final Map<String, AgentHandle> handles = new ConcurrentHashMap<>();

    public void register(String executionId, AgentHandle handle) {
        handles.put(executionId, handle);
    }

    public AgentHandle get(String executionId) {
        return handles.get(executionId);
    }

    public void unregister(String executionId) {
        handles.remove(executionId);
    }

    public int activeCount() {
        return handles.size();
    }
}

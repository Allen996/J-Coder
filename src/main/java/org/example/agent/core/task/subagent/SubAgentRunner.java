package org.example.agent.core.task.subagent;

/**
 * SubAgent 执行抽象（阶段 1 引入）。
 *
 * <p>主 Agent 通过 {@code dispatch_subtask} 工具调用本接口，传入 SubAgentTask，
 * 同步返回 SubAgentResult。实现方负责：
 * <ul>
 *   <li>用 taskId 作为 sessionId 构造独立 {@code SessionMessageStore}（与主 Agent 隔离）</li>
 *   <li>构造 {@code AgentTask.agentRole="subagent"}，由 {@code SpringAiReactAgentProvider}
 *       自动按 {@code ToolDescriptorRegistry.mainAgentOnly} 过滤掉主 Agent 专属工具</li>
 *   <li>同步跑完整 loop，TIMEOUT 时强制中断并返回 {@link SubAgentStatus#TIMEOUT}</li>
 * </ul>
 *
 * <p>实现方应保证 SubAgent 内部完整跑完（不被外部中断），除非超时。
 * 阶段 2 之前，{@code dispatch_subtask} 工具仍同步阻塞主 Agent loop。
 */
public interface SubAgentRunner {

    /**
     * 同步执行 SubAgent。返回结果不应为 null；失败时返回 FAILED + reason。
     */
    SubAgentResult run(SubAgentTask task);
}
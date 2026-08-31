package org.example.agent.core.task;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.budget.AgentBudget;

import java.util.List;
import java.util.Map;

/**
 * ReAct 运行时的输入契约。
 *
 * 业务方一行代码构建一个 Task，丢给 AgentRuntime 即可。模型、温度、tool 列表、
 * 提示词等内部细节都由 AgentRuntime 自行根据 promptId + budget + sessionRef 装配，
 * 业务方不需要直接接触 Spring AI Alibaba 的 ChatModel / ReactAgent。
 */
@Getter
@Builder
@ToString
public class AgentTask {

    /**
     * 调用方传入的会话 id（同 ChatController 现有的 id 字段语义）。
     * 用于 history 拉取 / LongTerm 检索 / 调用审计关联。
     */
    private final String sessionId;

    /**
     * 用户原始问题。
     */
    private final String input;

    /**
     * 当前任务的逻辑角色，例如 chat / planner / executor / supervisor。
     * 用于选择系统提示词模板与 token 预算策略（详见 agent-context.ContextBudgetPolicy）。
     */
    private final String role;

    /**
     * Agent 类型，用于工具注册过滤（阶段 1 引入）。
     * <ul>
     *   <li>"main" 或 null —— 主 Agent，拿到所有工具（包括主 Agent 专属工具）</li>
     *   <li>"subagent" —— SubAgent，按 {@code ToolDescriptorRegistry.mainAgentOnly} 过滤掉主 Agent 专属工具</li>
     * </ul>
     * 留空等价于 "main"。
     */
    @Builder.Default
    private final String agentRole = "main";

    /**
     * 提示词注册表 id（详见 agent-prompt）。未指定时使用默认 chat prompt。
     * 例如 "chat.react-assistant"、"aiops.planner"。
     */
    private final String promptId;

    /**
     * 透传给 PromptRegistry 的渲染变量。
     */
    private final Map<String, Object> promptVariables;

    /**
     * Token / 步数 / 超时的预算上限。AgentRuntime 在执行期间会向观察者广播，
     * 由 TokenBudgetObserver / LoopStepObserver / TimeoutObserver 强制终止。
     */
    private final AgentBudget budget;

    /**
     * 工具白名单（按 tool name 限定）。null 表示不做额外约束，
     * 由 AgentRuntime 暴露的所有可用 tool 决定。
     */
    private final List<String> toolAllowList;

    /**
     * 是否把会话历史上传入 AgentTask。
     * false 时表示全新会话（用于多 Agent 场景里子节点不感知外部历史）。
     */
    @Builder.Default
    private final boolean includeHistory = true;
}

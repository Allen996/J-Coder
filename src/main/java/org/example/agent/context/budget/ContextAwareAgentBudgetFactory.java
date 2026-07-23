package org.example.agent.context.budget;

import org.example.agent.core.budget.AgentBudget;
import org.springframework.stereotype.Component;

/**
 * 桥接 {@link ContextBudgetPolicy} 与 {@link AgentBudget}（part3.md §6.4 "复用现有 AgentBudget"）。
 *
 * <p>当 AgentTask 没有显式提供 budget 时，Runtime 用本工厂生成一个与 ContextBudgetPolicy
 * 对齐的默认 budget —— 确保 TokenBudgetObserver 能正确识别「per-call prompt 超窗口」。
 *
 * <p>字段映射：
 * <ul>
 *   <li>contextWindowMax       ← policy.contextWindowMax</li>
 *   <li>memoryTokenReservation ← policy.memoryTokenReservation</li>
 *   <li>maxSingleCallCompletion← policy.maxSingleCallCompletion</li>
 *   <li>maxSteps / maxTotalTokens 维持 AgentBudget 默认值</li>
 * </ul>
 */
@Component
public class ContextAwareAgentBudgetFactory {

    private final ContextBudgetPolicy policy;

    public ContextAwareAgentBudgetFactory(ContextBudgetPolicy policy) {
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
    }

    public AgentBudget defaultBudget() {
        return AgentBudget.builder()
                .contextWindowMax(policy.getContextWindowMax())
                .memoryTokenReservation(policy.getMemoryTokenReservation())
                .maxSingleCallCompletion(policy.getMaxSingleCallCompletion())
                .build();
    }
}
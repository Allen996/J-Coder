package org.example.agent.intent;

/**
 * L2 工具语义门控的决策。设计稿 §4.3。
 */
public enum ToolGateDecision {
    /** 工具与 L1 意图一致,直接放行。 */
    ALLOW,
    /** 弱一致或疑似漂移,放行但额外走 AuthorizationGate。 */
    WARN,
    /** 与 L1 强冲突,不下发工具,把结果以 ToolResponse 形式塞回 messages。 */
    BLOCK,
    /** 工具语义不对,在入口处改写为 suggested_alternative,再走 ALLOW 路径。 */
    REWRITE
}
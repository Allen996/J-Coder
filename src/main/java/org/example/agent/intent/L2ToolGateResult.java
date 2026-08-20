package org.example.agent.intent;

/**
 * L2 工具语义门控的输出。设计稿 §4.2。
 *
 * @param decision     ALLOW / WARN / BLOCK / REWRITE
 * @param confidence   0..1
 * @param reason       一句话原因,日志 + UI 用
 * @param suggestedAlternative 当 REWRITE/WARN 时给一个建议替换的工具名
 */
public record L2ToolGateResult(
        ToolGateDecision decision,
        double confidence,
        String reason,
        String suggestedAlternative
) {

    public static L2ToolGateResult allow(double conf, String reason) {
        return new L2ToolGateResult(ToolGateDecision.ALLOW, conf, reason, null);
    }

    public static L2ToolGateResult warn(double conf, String reason, String alt) {
        return new L2ToolGateResult(ToolGateDecision.WARN, conf, reason, alt);
    }

    public static L2ToolGateResult block(double conf, String reason) {
        return new L2ToolGateResult(ToolGateDecision.BLOCK, conf, reason, null);
    }

    public static L2ToolGateResult rewrite(double conf, String reason, String alt) {
        return new L2ToolGateResult(ToolGateDecision.REWRITE, conf, reason, alt);
    }
}
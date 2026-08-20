package org.example.agent.tool.sandbox;

import org.example.agent.tool.spi.ToolDescriptor;

import java.util.Map;

/**
 * 沙箱闸 4：用户授权闸。
 *
 * <p>对 {@link org.example.agent.tool.spi.ToolRisk#MEDIUM} /
 * {@link org.example.agent.tool.spi.ToolRisk#HIGH} 工具生效。
 *
 * <p>顺序保证：本闸在 {@link CommandGate} 之后执行 —— 黑名单永远先拦，
 * 授权 UI 不能放行 {@code rm -rf /} 之类的硬危险模式。
 *
 * <p>实现可以是 CLI 提示、GUI 弹窗、WebSocket 推送；接口只关心"用户给不给、给多久"。
 */
public interface AuthorizationGate {

    /**
     * 单次授权决策结果。
     *
     * @param approved     用户是否同意本次调用
     * @param sessionWide  是否在本次会话内永久放行该工具（用户输入 always/yes-to-all）
     * @param reason       用户给出的备注（可空）。审计日志用
     */
    record Decision(boolean approved, boolean sessionWide, String reason) {
        public static Decision allowOnce() {
            return new Decision(true, false, null);
        }
        public static Decision allowForSession() {
            return new Decision(true, true, "user chose always-for-session");
        }
        public static Decision deny(String reason) {
            return new Decision(false, false, reason);
        }
    }

    /**
     * 请求用户授权。
     *
     * <p>必须阻塞直到用户给出决策或线程被中断。中断时返回 deny。
     *
     * @param descriptor 工具元数据（risk / description 等）
     * @param toolName   工具方法名
     * @param args       本次调用的入参（已解析为 Map）
     */
    Decision authorize(ToolDescriptor descriptor, String toolName, Map<String, Object> args);

    /**
     * 该工具是否已在会话级被放行（用户在上一次选择过 "always"）。
     */
    boolean isSessionAllowed(String toolName);

    /**
     * 将会话级放行集合增加一个工具。
     */
    void rememberSessionAllow(String toolName);
}
package org.example.agent.tool.sandbox;

import org.example.agent.tool.config.CliToolProperties;
import org.example.cli.session.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CliAuthorizationGate} 的解析 / 模式覆盖。
 *
 * <p>用 ByteArrayInputStream 喂入用户回答，验证：
 * <ul>
 *   <li>PROMPT 模式 + y → 放行一次</li>
 *   <li>PROMPT 模式 + n → 拒绝</li>
 *   <li>PROMPT 模式 + a → 放行 + 写入 sessionAllowed</li>
 *   <li>AUTO_APPROVE 模式 → 不读 stdin,直接放行</li>
 *   <li>AUTO_DENY 模式 → 不读 stdin,直接拒绝</li>
 *   <li>SessionState.autoApprove=true → 覆盖 mode 为 AUTO_APPROVE</li>
 *   <li>yml.alwaysAllow 预填 → 不读 stdin,直接 isSessionAllowed true</li>
 * </ul>
 */
class CliAuthorizationGateTest {

    private static final org.example.agent.tool.spi.ToolDescriptor DESC =
            org.example.agent.tool.spi.ToolDescriptor.high("run_shell", "shell tool");

    @Test
    @DisplayName("y → 放行一次,不入 sessionAllowed")
    void yesOnce() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.PROMPT, List.of())),
                "y\n");
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of("command", "ls"));

        assertTrue(d.approved());
        assertFalse(d.sessionWide());
        assertFalse(gate.isSessionAllowed("run_shell"));
    }

    @Test
    @DisplayName("n → 拒绝,reason 含 user said no")
    void noDeny() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.PROMPT, List.of())),
                "n\n");
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of());

        assertFalse(d.approved());
        assertEquals("user said no", d.reason());
    }

    @Test
    @DisplayName("empty / unknown answer → 拒绝(默认 n)")
    void emptyIsDeny() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.PROMPT, List.of())),
                "\n");
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of());
        assertFalse(d.approved());
    }

    @Test
    @DisplayName("a → 放行 + sessionAllowed,后续不读 stdin")
    void alwaysSession() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.PROMPT, List.of())),
                "always\n");
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of());
        assertTrue(d.approved());
        assertTrue(d.sessionWide());
        assertTrue(gate.isSessionAllowed("run_shell"));

        // 第二次调用,授权闸因为 isSessionAllowed=true 短路,不再读 stdin
        AuthorizationGate.Decision d2 = gate.authorize(DESC, "run_shell", Map.of());
        assertTrue(d2.approved());
        assertFalse(d2.sessionWide(), "短路路径不重复写 sessionWide");
    }

    @Test
    @DisplayName("AUTO_APPROVE → 不读 stdin,直接放行(用空 stdin 验证没被消费)")
    void autoApproveSilent() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.AUTO_APPROVE, List.of())),
                "");
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of());
        assertTrue(d.approved());
        assertFalse(d.sessionWide());
    }

    @Test
    @DisplayName("AUTO_DENY → 不读 stdin,直接拒绝")
    void autoDenySilent() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.AUTO_DENY, List.of())),
                "");
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of());
        assertFalse(d.approved());
        assertEquals("mode=AUTO_DENY", d.reason());
    }

    @Test
    @DisplayName("SessionState.autoApprove=true → 强制 AUTO_APPROVE,即使 yml 配 PROMPT")
    void sessionOverrideForcesApprove() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.PROMPT, List.of())),
                "");
        ((SessionState) sessionOf(gate)).setAutoApprove(true);
        AuthorizationGate.Decision d = gate.authorize(DESC, "run_shell", Map.of());
        assertTrue(d.approved(), "/auto-approve 后即使 yml 是 PROMPT 也应放行");
    }

    @Test
    @DisplayName("yml.alwaysAllow 预填 → 启动即 sessionAllowed,不读 stdin")
    void yamlAlwaysAllowPresetsSession() {
        var gate = newGate(new CliToolProperties(true, 60_000, 0, 8, null,
                new CliToolProperties.Authorization(CliToolProperties.Authorization.Mode.PROMPT,
                        List.of("run_shell", "git_commit"))),
                "");
        assertTrue(gate.isSessionAllowed("run_shell"));
        assertTrue(gate.isSessionAllowed("git_commit"));
    }

    // ============== helpers ==============

    /** 反射拿 session,只读字段。 */
    private static Object sessionOf(CliAuthorizationGate gate) {
        try {
            var f = CliAuthorizationGate.class.getDeclaredField("session");
            f.setAccessible(true);
            return f.get(gate);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static CliAuthorizationGate newGate(CliToolProperties props, String stdinContent) {
        SessionState s = new SessionState();
        return new CliAuthorizationGate(
                props, s,
                new ByteArrayInputStream(stdinContent.getBytes(StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
    }
}
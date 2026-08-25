package org.example.agent.tool.sandbox;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.spi.ToolDescriptor;
import org.example.cli.session.SessionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLI 版用户授权闸。从 stdin/stdout 阻塞读一行决策。
 *
 * <p>优先级（从高到低）：
 * <ol>
 *   <li>{@code yml.alwaysAllow} 配置 → 直接放行（视为会话级）</li>
 *   <li>{@code SessionState.autoApprove == true} 或 mode=AUTO_APPROVE → 直接放行</li>
 *   <li>{@code mode=AUTO_DENY} → 直接拒绝</li>
 *   <li>其他 → 阻塞读 stdin，y/yes 放行一次，a/always 放行整次会话，其余视为拒绝</li>
 * </ol>
 *
 * <p>线程模型：{@link org.example.agent.tool.gateway.ToolGateway} 在 toolExecutor 线程上调用本类，
 * REPL 此刻正阻塞在 {@code CountDownLatch.await}，终端空闲。读 stdin 不会与 REPL 抢键。
 * prompt 自身用 {@code synchronized} 串行化 —— 即便同一时刻并发多个工具调用，也一次只弹一个。
 */
@Slf4j
@Component
public class CliAuthorizationGate implements AuthorizationGate {

    private final CliToolProperties properties;
    private final SessionState session;
    private final BufferedReader in;
    private final PrintWriter out;
    private final Set<String> sessionAllowed = ConcurrentHashMap.newKeySet();

    @Autowired
    public CliAuthorizationGate(CliToolProperties properties, SessionState session) {
        this(properties, session, System.in, new PrintWriter(System.out, true, StandardCharsets.UTF_8));
    }

    /** 测试用：注入自定义 Reader / Writer。 */
    public CliAuthorizationGate(CliToolProperties properties,
                                SessionState session,
                                InputStream input,
                                PrintWriter output) {
        this.properties = properties;
        this.session = session;
        this.in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.out = output;
        // 启动时把 yml.alwaysAllow 全部灌进 sessionAllowed
        Set<String> preset = new HashSet<>();
        for (String tool : properties.authorization().alwaysAllow()) {
            if (tool != null && !tool.isBlank()) preset.add(tool.trim());
        }
        sessionAllowed.addAll(preset);
        if (!preset.isEmpty()) {
            log.info("cli.tool.authorization.alwaysAllow pre-approved: {}", preset);
        }
    }

    @Override
    public boolean isSessionAllowed(String toolName) {
        if (toolName == null) return false;
        return sessionAllowed.contains(toolName);
    }

    @Override
    public void rememberSessionAllow(String toolName) {
        if (toolName == null) return;
        sessionAllowed.add(toolName);
    }

    @Override
    public Decision authorize(ToolDescriptor descriptor, String toolName, Map<String, Object> args) {
        // 0. yml alwaysAllow 已在构造时灌入 sessionAllowed，gateway 会先查 isSessionAllowed

        // 1. SessionState.autoApprove 覆盖 mode（/auto-approve 命令即时翻转）
        CliToolProperties.Authorization.Mode mode =
                session.isAutoApprove() ? CliToolProperties.Authorization.Mode.AUTO_APPROVE
                                        : properties.authorization().mode();

        // 2. session 已放行（用户上一轮选过 always 或 yml alwaysAllow）
        if (sessionAllowed.contains(toolName)) {
            return Decision.allowOnce();
        }

        // 3. AUTO_APPROVE 静默放行（开发模式；写一条 warn 留痕）
        if (mode == CliToolProperties.Authorization.Mode.AUTO_APPROVE) {
            log.warn("auto-approve {} (risk={}, mode=AUTO_APPROVE)", toolName, descriptor.risk());
            return Decision.allowOnce();
        }

        // 4. AUTO_DENY 静默拒绝
        if (mode == CliToolProperties.Authorization.Mode.AUTO_DENY) {
            log.warn("auto-deny {} (risk={}, mode=AUTO_DENY)", toolName, descriptor.risk());
            return Decision.deny("mode=AUTO_DENY");
        }

        // 5. PROMPT —— 阻塞等用户决策
        return promptUser(descriptor, toolName, args);
    }

    private Decision promptUser(ToolDescriptor descriptor, String toolName, Map<String, Object> args) {
        synchronized (this) {
            renderPrompt(descriptor, toolName, args);
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (Exception ex) {
                log.warn("auth prompt read failed for {}: {}", toolName, ex.toString());
                return Decision.deny("stdin read failed: " + ex.getClass().getSimpleName());
            }
            if (line == null) {
                return Decision.deny("stdin closed (EOF)");
            }
            String answer = line.trim().toLowerCase();
            return switch (answer) {
                case "y", "yes" -> {
                    log.info("user approved {} (risk={})", toolName, descriptor.risk());
                    yield Decision.allowOnce();
                }
                case "a", "always" -> {
                    sessionAllowed.add(toolName);
                    log.info("user always-allowed {} for session (risk={})", toolName, descriptor.risk());
                    yield Decision.allowForSession();
                }
                default -> {
                    log.info("user denied {} (input='{}')", toolName, line.trim());
                    yield Decision.deny("user said no");
                }
            };
        }
    }

    private void renderPrompt(ToolDescriptor descriptor, String toolName, Map<String, Object> args) {
        out.println();
        out.println("  ┌─ auth required ─────────────────────────────────────────");
        out.printf("  │ tool  : %s  (risk=%s)%n", toolName, descriptor.risk());
        out.printf("  │ about : %s%n", descriptor.description());
        if (args != null && !args.isEmpty()) {
            out.println("  │ args  :");
            for (Map.Entry<String, Object> e : args.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (v.length() > 200) v = v.substring(0, 200) + "…";
                out.printf("  │   %s = %s%n", e.getKey(), v);
            }
        }
        out.println("  │ allow ? [y=once / a=always-this-session / n=deny]  default n");
        out.print  ("  └─> ");
    }

    // 仅测试可见：让单测能断言内部状态
    Set<String> sessionAllowedForTest() {
        return Collections.unmodifiableSet(sessionAllowed);
    }
}
package org.example.agent.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CLI 意图确认/反问交互。设计稿 §3.3:
 *
 * <ul>
 *   <li>OFFER (0.60 ≤ conf < 0.85):打印 1..N 候选,用户选号或输入 0 跳过</li>
 *   <li>CLARIFY (conf < 0.60):一句话 + 候选按钮,用户应答后再走一遍 L1</li>
 * </ul>
 *
 * <p>线程模型:同 {@code CliAuthorizationGate} —— ReActLoop 在 worker 线程上调用本类,
 * REPL 主线程此刻阻塞在 CountDownLatch.await,stdin 不会与 REPL 抢键。
 *
 * <p>{@link #suppress} 在非交互场景(测试 / eval 脚本)置 true,直接按主标签落定。
 */
@Component
public class IntentPrompter {

    private static final Logger log = LoggerFactory.getLogger(IntentPrompter.class);

    private final BufferedReader in;
    private final PrintWriter out;
    private volatile boolean suppress = false;

    public IntentPrompter() {
        this(System.in, new PrintWriter(System.out, true, StandardCharsets.UTF_8));
    }

    public IntentPrompter(InputStream input, PrintWriter output) {
        this.in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.out = output;
    }

    /** 测试 / 非交互场景:跳过阻塞读 stdin,直接返回主标签。 */
    public void setSuppress(boolean suppress) {
        this.suppress = suppress;
    }

    /**
     * Tier.OFFER:列出候选,等用户 1..N 选择。
     *
     * @return 选定的标签;若用户输入 0 或读不到 → 返回 primary(等同于"用模型自评")
     */
    public synchronized IntentLabel promptOffer(String userInput, L1IntentResult result) {
        if (suppress) return result.primary();
        List<L1IntentResult.Candidate> cands = result.candidates();
        int n = Math.min(3, cands == null ? 0 : cands.size());
        if (n == 0) return result.primary();
        out.println();
        out.println("  ┌─ intent unclear, pick one ─────────────────────────────");
        out.printf("  │ input : %s%n", truncate(userInput, 80));
        for (int i = 0; i < n; i++) {
            L1IntentResult.Candidate c = cands.get(i);
            out.printf("  │  %d) %-15s  (score=%.2f)%n", i + 1, c.label().name(), c.score());
        }
        out.printf("  │  0) %s (use model default)%n", result.primary().name());
        out.print("  └─> ");
        out.flush();
        if (in == null) return result.primary();
        String line;
        try {
            line = in.readLine();
        } catch (Exception ex) {
            log.warn("intent offer prompt read failed: {}", ex.toString());
            return result.primary();
        }
        if (line == null) return result.primary();
        String trimmed = line.trim();
        if (trimmed.isEmpty() || "0".equals(trimmed)) return result.primary();
        try {
            int pick = Integer.parseInt(trimmed);
            if (pick >= 1 && pick <= n) return cands.get(pick - 1).label();
        } catch (NumberFormatException ignore) { /* fall through */ }
        return result.primary();
    }

    /**
     * Tier.CLARIFY:用一句话反问,等用户输入新文本(同一主标签的再次 L1)。
     * <p>实际调用方拿到的是澄清后的"用户文本",由它决定是否再调一次 L1。
     */
    public synchronized String promptClarify(String userInput, L1IntentResult result) {
        if (suppress) return userInput;
        out.println();
        out.println("  ┌─ clarify intent ──────────────────────────────────────");
        out.printf("  │ input    : %s%n", truncate(userInput, 80));
        out.printf("  │ guessed  : %s  (conf=%.2f)%n", result.primary().name(), result.confidence());
        out.println("  │ ask      : 想做什么?(1 行简短说明,空回车 = 用上述猜测)");
        out.print("  └─> ");
        out.flush();
        if (in == null) return userInput;
        try {
            String line = in.readLine();
            if (line == null || line.isBlank()) return userInput;
            return line.trim();
        } catch (Exception ex) {
            log.warn("intent clarify prompt read failed: {}", ex.toString());
            return userInput;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
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

    /**
     * 第三阶段新增:为"强 pattern 触发"生成模板化中文反问文本。
     *
     * <p>不走 LLM,固定模板(Q4-A):
     * <pre>
     * 我看到你说的是 '&lt;原句&gt;'，但你似乎想 &lt;LLM primary 中文动作&gt;，是这样吗？
     * </pre>
     *
     * <p>命中多个 pattern 类型时,在句末追加一句说明:
     * <pre>
     * 另:我也注意到你说了 &lt;其他类型中文动作&gt;。
     * </pre>
     *
     * <p>{@code suppress=true}(eval / 测试场景)也返回反问文本,不递归 classifyFresh,
     * 让 eval 能观测反问是否被触发(决策 A)。
     *
     * @param userInput   用户原句
     * @param llmPrimary  LLM 给出的 primary(必须是写读类,否则不调用本方法)
     * @param types       StrongPatternClassifier 命中的强 pattern 类型列表(可为 null / 空)
     * @return 中文反问文本;types 为空时返回 null
     */
    public String promptStrongPatternClarify(String userInput, IntentLabel llmPrimary,
                                              List<StrongPatternClassifier.PatternType> types) {
        if (types == null || types.isEmpty() || llmPrimary == null) {
            return null;
        }
        String action = chineseAction(llmPrimary);
        if (action == null) {
            return null; // 非写读类,本路径不适用
        }
        String firstType = types.get(0).chineseHint();
        StringBuilder sb = new StringBuilder();
        sb.append("我看到你说的是 '").append(truncate(userInput, 60))
          .append("'，但你似乎想 ").append(action)
          .append("，是这样吗？");
        if (types.size() > 1) {
            sb.append(" 另:我也注意到你似乎想 ");
            for (int i = 1; i < types.size(); i++) {
                if (i > 1) sb.append(" / ");
                sb.append(types.get(i).chineseHint());
            }
            sb.append("。");
        }
        // suppress 下不打印 UI,但仍返回文本供 eval / 日志观测
        if (!suppress) {
            out.println();
            out.println("  ┌─ clarify by strong pattern ───────────────────────────");
            out.printf("  │ input    : %s%n", truncate(userInput, 80));
            out.printf("  │ patterns : %s%n", types);
            out.printf("  │ guessed  : %s%n", llmPrimary.name());
            out.printf("  │ ask      : %s%n", sb);
            out.flush();
        }
        return sb.toString();
    }

    private static String chineseAction(IntentLabel label) {
        if (label == null) return null;
        return switch (label) {
            case READ_CODE -> "读代码";
            case WRITE_PROJECT -> "改代码";
            case RUN_COMMAND -> "执行命令";
            case PLANNING -> "做规划";
            // CHAT_QA 不走反问路径 —— 已经是非编程意图,直接走模板响应即可
            default -> null;
        };
    }
}
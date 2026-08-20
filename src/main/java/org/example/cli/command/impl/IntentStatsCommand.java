package org.example.cli.command.impl;

import org.example.agent.intent.IntentGate;
import org.example.agent.intent.L1IntentResult;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设计稿 §8:"加 /intent-stats slash 命令,复用现有 CliCommand 体系,
 * 展示近 N 次 L1/L2 命中率与置信度分布(开发期用,上线可隐)"。
 *
 * <p>首期只展示 L1 结果(L2 历史通过 IntentContext 串行写入,但跨 turn 数据
 * 暂未集中收集;后续可挂一个全 session 的 collector)。
 */
@Component
public class IntentStatsCommand implements SlashCommand {

    private final IntentGate intentGate;

    public IntentStatsCommand(IntentGate intentGate) {
        this.intentGate = intentGate;
    }

    @Override
    public String name() {
        return "intent-stats";
    }

    @Override
    public String description() {
        return "show recent L1 intent classifications (debug)";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        List<L1IntentResult> recent = intentGate.recentResults();
        if (recent.isEmpty()) {
            ctx.out().println("(no recent L1 results; run a turn first)");
            ctx.out().flush();
            return 0;
        }
        ctx.out().println("  recent L1 (n=" + recent.size() + "):");
        Map<String, Integer> labelCounts = new HashMap<>();
        Map<String, Integer> tierCounts = new HashMap<>();
        int fallbacks = 0;
        double confSum = 0.0;
        for (L1IntentResult r : recent) {
            labelCounts.merge(r.primary().name(), 1, Integer::sum);
            tierCounts.merge(r.tier().name(), 1, Integer::sum);
            if (r.fallback()) fallbacks++;
            confSum += r.confidence();
        }
        ctx.out().println("    label distribution:");
        labelCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> ctx.out().println("      " + e.getKey() + " = " + e.getValue()));
        ctx.out().println("    tier distribution:");
        tierCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> ctx.out().println("      " + e.getKey() + " = " + e.getValue()));
        ctx.out().printf("    avg confidence: %.3f%n", confSum / recent.size());
        ctx.out().printf("    fallbacks:      %d / %d%n", fallbacks, recent.size());
        ctx.out().println("    last 5:");
        int from = Math.max(0, recent.size() - 5);
        for (int i = from; i < recent.size(); i++) {
            L1IntentResult r = recent.get(i);
            ctx.out().printf("      [%d] %-15s conf=%.2f model=%s fallback=%s%n",
                    i, r.primary(), r.confidence(),
                    r.modelRouteHint(), r.fallback());
        }
        ctx.out().flush();
        return 0;
    }
}
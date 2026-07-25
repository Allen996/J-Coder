package org.example.cli.command.impl;

import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.layer.DynamicLayer;
import org.example.agent.context.layer.StaticLayer;
import org.example.agent.context.observability.PromptDumpObserver;
import org.example.agent.context.session.SessionMessageStore;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * /context —— 实时查看两层字典上下文状态（part3.md §6.1）。
 *
 * <p>展示内容：
 * <ol>
 *   <li>【顶层摘要】Static Layer / Dynamic Layer 各自的 token 估算与预算。</li>
 *   <li>【Static Layer 字典】4 个 key（role_definition / tool_list / code_writing_cot / runtime_meta）
 *       各自的 byte / token 估算、来源、加载时间。</li>
 *   <li>【Dynamic Layer 字典】5 个 key（messages / mid_term / long_term / memory_index / ephemeral）
 *       各自的 byte / token 估算、来源、加载时间。</li>
 *   <li>【最近一次 LLM Prompt】从 {@link PromptDumpObserver} 读。</li>
 * </ol>
 */
@Component
public class ContextCommand implements SlashCommand {

    private final ContextBuilder contextBuilder;
    private final ContextBudgetPolicy policy;
    private final StaticLayer staticLayer;
    private final DynamicLayer dynamicLayer;
    private final SessionMessageStore sessionStore;
    private final PromptDumpObserver promptDumpObserver;

    public ContextCommand(ContextBuilder contextBuilder,
                          ContextBudgetPolicy policy,
                          StaticLayer staticLayer,
                          DynamicLayer dynamicLayer,
                          SessionMessageStore sessionStore,
                          PromptDumpObserver promptDumpObserver) {
        this.contextBuilder = contextBuilder;
        this.policy = policy == null ? ContextBudgetPolicy.defaultPolicy() : policy;
        this.staticLayer = staticLayer;
        this.dynamicLayer = dynamicLayer;
        this.sessionStore = sessionStore;
        this.promptDumpObserver = promptDumpObserver;
    }

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "show 2-layer dictionary context (Static 4 keys + Dynamic 5 keys) + last LLM prompt";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        boolean wantFull = args != null && (args.contains("--full") || args.contains("-f"));
        int maxChars = wantFull ? Integer.MAX_VALUE : 200;

        ctx.out().println();
        ctx.out().println("=== 2-LAYER DICTIONARY OVERVIEW ===");
        ctx.out().println();

        // 顶层摘要
        long staticTokens = staticLayer.totalEstimatedTokens();
        long dynamicTokens = dynamicLayer.totalEstimatedTokens();
        long dynamicReserved = policy.dynamicReserved();
        ctx.out().printf("  Static Layer   budget=%d  actual≈%d tokens  (loaded=%s)%n",
                policy.getStaticReserved(), staticTokens,
                staticLayer.lastLoadedAt() == null ? "(never)" : staticLayer.lastLoadedAt().toString());
        ctx.out().printf("  Dynamic Layer  budget=%d  actual≈%d tokens  (%.1f%%)%n",
                dynamicReserved, dynamicTokens,
                dynamicReserved == 0 ? 0 : (dynamicTokens * 100.0 / dynamicReserved));
        ctx.out().printf("  Context Window %d (memory=%d, completion=%d)%n",
                policy.getContextWindowMax(),
                policy.getMemoryTokenReservation(),
                policy.getMaxSingleCallCompletion());
        ctx.out().println();

        // Static Layer 字典
        ctx.out().println("  ── Static Layer (4 keys) ─────────────────────────────────");
        printKeyTable(ctx, staticLayer.declaredKeys(), staticLayer);
        ctx.out().println();

        // Dynamic Layer 字典
        ctx.out().println("  ── Dynamic Layer (5 keys) ────────────────────────────────");
        printKeyTable(ctx, dynamicLayer.declaredKeys(), dynamicLayer);
        ctx.out().println();

        // 最近一次 LLM Prompt
        PromptDumpObserver.Snapshot snap = promptDumpObserver == null ? null : promptDumpObserver.latestSnapshot();
        if (snap == null) {
            ctx.out().println("(no LLM prompt captured yet — send a message to let the agent run)");
        } else {
            ctx.out().printf("=== LAST LLM PROMPT (step=%d) ===%n", snap.getStepIndex());
            if (promptDumpObserver != null) {
                promptDumpObserver.dumpSnapshot(snap, ctx.out(), maxChars, false);
            }
        }
        ctx.out().flush();
        return 0;
    }

    private void printKeyTable(CliContext ctx, ContextKey[] keys, org.example.agent.context.layer.ContextLayer layer) {
        ctx.out().printf("    %-18s  %-10s  %-36s  %s%n", "key", "tokens", "source", "last refreshed");
        for (ContextKey key : keys) {
            ContextEntry entry = layer.get(key).orElse(null);
            if (entry == null) {
                ctx.out().printf("    %-18s  %-10s  %-36s  %s%n",
                        key.wireName(), "0", "(missing)", "-");
            } else {
                String text = entry.getText();
                String sizeHint;
                if (text == null && entry.getStructuredPayload() != null) {
                    sizeHint = "(structured)";
                } else if (text == null || text.isEmpty()) {
                    sizeHint = "(empty)";
                } else {
                    sizeHint = String.valueOf(entry.getEstimatedTokens());
                }
                ctx.out().printf("    %-18s  %-10s  %-36s  %s%n",
                        key.wireName(),
                        sizeHint,
                        entry.getSourceRef() == null ? "-" : entry.getSourceRef(),
                        entry.getLastRefreshedAt() == null ? "-" : entry.getLastRefreshedAt().toString());
            }
        }
    }

    // 兼容旧接口（保持向后兼容）
    public static List<Message> messagesForSession(SessionMessageStore store, String sessionId) {
        return store.getOrCreate(sessionId).snapshot();
    }
}

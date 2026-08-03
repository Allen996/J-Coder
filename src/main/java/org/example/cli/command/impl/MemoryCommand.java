package org.example.cli.command.impl;

import org.example.agent.context.memory.LongTermMaintainer;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.memory.MemoryIndexSynchronizer;
import org.example.agent.context.memory.MemoryTurnHook;
import org.example.agent.context.memory.MidTermStore;
import org.example.agent.context.memory.PendingLongTermCandidates;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /memory} —— 记忆系统用户入口（part4.md §7.2 / §7.6 / §7.7）。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code /memory} 或 {@code /memory pending} —— 列出待确认的 long-term 候选条目。</li>
 *   <li>{@code /memory confirm id[,id...] [--topic=<slug>]} —— 接受候选，按主题归入对应主题文件；新主题则创建。</li>
 *   <li>{@code /memory reject id[,id...]} —— 拒绝候选。</li>
 *   <li>{@code /memory show} —— 显示 short/mid/long/memory_index 的当前快照。</li>
 *   <li>{@code /memory flush} —— 强制 mid-term 整体重生成。</li>
 *   <li>{@code /memory tick} —— 强制 long-term 提取本 session 当前未读到的短期记忆。</li>
 * </ul>
 */
@Component
public class MemoryCommand implements SlashCommand {

    private final PendingLongTermCandidates pending;
    private final LongTermStore longTermStore;
    private final MidTermStore midTermStore;
    private final MemoryIndex memoryIndex;
    private final MemoryTurnHook memoryTurnHook;
    private final LongTermMaintainer longTermMaintainer;
    private final MemoryIndexSynchronizer indexSync;

    public MemoryCommand(PendingLongTermCandidates pending,
                         LongTermStore longTermStore,
                         MidTermStore midTermStore,
                         MemoryIndex memoryIndex,
                         MemoryTurnHook memoryTurnHook,
                         LongTermMaintainer longTermMaintainer,
                         MemoryIndexSynchronizer indexSync) {
        this.pending = pending;
        this.longTermStore = longTermStore;
        this.midTermStore = midTermStore;
        this.memoryIndex = memoryIndex;
        this.memoryTurnHook = memoryTurnHook;
        this.longTermMaintainer = longTermMaintainer;
        this.indexSync = indexSync;
    }

    @Override
    public String name() { return "memory"; }

    @Override
    public String description() {
        return "manage memory: pending | confirm <ids> [--topic=<slug>] | reject <ids> | show | flush | tick";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        String sessionId = ctx.session().getSessionId();
        String trimmed = args == null ? "" : args.strip();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("pending")) {
            return listPending(ctx);
        }
        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase();
        String subArgs = parts.length > 1 ? parts[1].trim() : "";
        switch (sub) {
            case "confirm": return confirmCandidates(subArgs, sessionId, ctx);
            case "reject":  return rejectCandidates(subArgs, ctx);
            case "show":    return showAll(sessionId, ctx);
            case "flush":   return flushMid(sessionId, ctx);
            case "tick":    return tickLongTerm(sessionId, ctx);
            default:
                ctx.out().printf("unknown /memory sub-command: %s%n  try: pending | confirm <ids> [--topic=<slug>] | reject <ids> | show | flush | tick%n", sub);
                ctx.out().flush();
                return 2;
        }
    }

    private int listPending(CliContext ctx) {
        List<PendingLongTermCandidates.Pending> items = pending.snapshot();
        ctx.out().println();
        ctx.out().printf("=== MEMORY PENDING (%d) ===%n", items.size());
        if (items.isEmpty()) {
            ctx.out().println("  (empty) LongTermMaintainer will append candidates asynchronously.");
        } else {
            for (PendingLongTermCandidates.Pending p : items) {
                ctx.out().printf("  %-8s  imp=%d  [%s] topic=%s  %s%n",
                        p.getId(), p.getImportance(),
                        p.getCategory().wire,
                        p.getTopic().equals("NEW") ? "NEW(" + p.getTopicProposal() + ")" : p.getTopic(),
                        p.getContent());
                if (!p.getTitle().isBlank()) {
                    ctx.out().printf("             title:    %s%n", trim(p.getTitle()));
                }
                if (!p.getEvidence().isBlank()) {
                    ctx.out().printf("             evidence: %s%n", trim(p.getEvidence()));
                }
                if (!p.getReason().isBlank()) {
                    ctx.out().printf("             reason:   %s%n", trim(p.getReason()));
                }
            }
            ctx.out().println();
            ctx.out().println("  Use /memory confirm <ids> [--topic=<slug>]  to write to topic file");
            ctx.out().println("  Use /memory reject  <ids>                   to discard");
        }
        ctx.out().flush();
        return 0;
    }

    private int confirmCandidates(String argsLine, String sessionId, CliContext ctx) {
        if (argsLine.isBlank()) {
            ctx.out().println("confirm: specify candidate ids, e.g. /memory confirm cand-1,cand-3 --topic=my-topic");
            ctx.out().flush();
            return 2;
        }
        // 解析 --topic=<slug>
        String topicOverride = null;
        String idsPart = argsLine;
        int topicIdx = argsLine.indexOf("--topic=");
        if (topicIdx >= 0) {
            String tail = argsLine.substring(topicIdx + "--topic=".length());
            int space = tail.indexOf(' ');
            if (space >= 0) {
                topicOverride = tail.substring(0, space);
                idsPart = (argsLine.substring(0, topicIdx) + tail.substring(space)).strip();
            } else {
                topicOverride = tail.strip();
                idsPart = argsLine.substring(0, topicIdx).strip();
            }
        }
        List<String> ids = Arrays.stream(idsPart.split("[,\\s]+"))
                .filter(s -> !s.isBlank()).toList();
        List<PendingLongTermCandidates.Pending> matches = pending.findByIds(ids);
        if (matches.isEmpty()) {
            ctx.out().printf("confirm: no matching candidates for %s%n", idsPart);
            ctx.out().flush();
            return 2;
        }
        int confirmed = 0;
        for (PendingLongTermCandidates.Pending p : matches) {
            String targetSlug = topicOverride != null && !topicOverride.isBlank()
                    ? topicOverride
                    : (p.getTopic().equals("NEW") ? p.getTopicProposal() : p.getTopic());
            if (targetSlug == null || targetSlug.isBlank()) targetSlug = "general";
            LongTermStore.Topic topic = longTermStore.findTopicBySlugOrSeq(targetSlug);
            if (topic == null) {
                topic = longTermStore.createTopic(targetSlug, "", java.util.List.of(), java.util.List.of(), 3, false);
            }
            LongTermStore.Entry entry = new LongTermStore.Entry(
                    "",
                    p.getCategory(), p.getContent(), p.getImportance(), p.isPinned(),
                    p.getEvidence(), p.getReason(),
                    topic.getSlug(),
                    Instant.now(), false);
            longTermStore.appendToTopic(topic, entry);
            memoryIndex.add(topic.filename(), topic.getSummary().isEmpty() ? "topic " + topic.getSlug() : topic.getSummary());
            pending.remove(p.getId());
            confirmed++;
        }
        ctx.out().printf("confirm: %d candidate(s) appended to %d topic file(s)%n",
                confirmed, longTermStore.loadTopics().size());
        ctx.out().flush();
        return 0;
    }

    private int rejectCandidates(String idsArg, CliContext ctx) {
        if (idsArg.isBlank()) {
            ctx.out().println("reject: specify candidate ids");
            ctx.out().flush();
            return 2;
        }
        List<String> ids = Arrays.stream(idsArg.split("[,\\s]+"))
                .filter(s -> !s.isBlank()).toList();
        int removed = 0;
        for (String id : ids) {
            if (pending.remove(id) != null) removed++;
        }
        ctx.out().printf("reject: %d candidate(s) discarded%n", removed);
        ctx.out().flush();
        return 0;
    }

    private int showAll(String sessionId, CliContext ctx) {
        ctx.out().println();
        ctx.out().println("=== MEMORY ===");

        MidTermStore.MidTerm mt = midTermStore.loadOrEmpty(sessionId);
        if (mt == null) {
            ctx.out().println("  mid-term  (no file yet)");
        } else {
            ctx.out().printf("  mid-term  session=%s updated=%s imp=%d%n",
                    mt.getSessionId(), mt.getUpdatedAt(), mt.getImportance());
            ctx.out().printf("    summary:        %s%n", trim(mt.getSessionSummary()));
            ctx.out().printf("    done:           %s%n", trim(String.join("; ", mt.getCrossSessionDone())));
            ctx.out().printf("    inProgress:     %s%n", trim(String.join("; ", mt.getCrossSessionInProgress())));
            ctx.out().printf("    blocked:        %s%n", trim(String.join("; ", mt.getCrossSessionBlocked())));
            ctx.out().printf("    userFocus:      %s%n", trim(String.join("; ", mt.getUserFocus())));
            ctx.out().printf("    rules:          %s%n", trim(String.join("; ", mt.getContextualRules())));
        }

        java.util.List<LongTermStore.Topic> topics = longTermStore.loadTopics();
        ctx.out().printf("  long-term topics (%d):%n", topics.size());
        for (LongTermStore.Topic t : topics) {
            ctx.out().printf("    [%s] imp=%d pinned=%s entries=%d — %s%n",
                    t.getSeq() + "-" + t.getSlug(),
                    t.getImportance(), t.isPinned(), t.getEntryCount(),
                    t.getSummary().isEmpty() ? "(no summary)" : t.getSummary());
        }

        ctx.out().printf("  memory-index (%d entries, LRU 取消):%n", memoryIndex.loadOrEmpty().size());
        for (MemoryIndex.IndexEntry e : memoryIndex.loadOrEmpty()) {
            ctx.out().printf("    %s — %s%n", e.getPath(), trim(e.getSummary()));
        }

        ctx.out().flush();
        return 0;
    }

    private int flushMid(String sessionId, CliContext ctx) {
        MidTermStore.MidTerm out = memoryTurnHook.regenerateForSession(sessionId);
        if (out == null) {
            ctx.out().printf("flush: mid-term regeneration skipped (LLM unavailable or no session history)%n");
        } else {
            ctx.out().printf("flush: mid-term regenerated — summary=%d chars, done=%d items%n",
                    out.getSessionSummary().length(),
                    out.getCrossSessionDone().size());
        }
        ctx.out().flush();
        return 0;
    }

    private int tickLongTerm(String sessionId, CliContext ctx) {
        if (longTermMaintainer == null) {
            ctx.out().println("tick: LongTermMaintainer not available");
            ctx.out().flush();
            return 0;
        }
        longTermMaintainer.setActiveSessionId(sessionId);
        int n = longTermMaintainer.processSession(sessionId);
        ctx.out().printf("tick: processed session %s, %d new candidate(s)%n", sessionId, n);
        ctx.out().flush();
        return 0;
    }

    private static String trim(String s) {
        if (s == null) return "";
        String[] first = s.split("\n", 2);
        String head = first[0].strip();
        return head.length() > 120 ? head.substring(0, 120) + "..." : head;
    }
}
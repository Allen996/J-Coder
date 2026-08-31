package org.example.cli.command.impl;

import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.memory.LongTermMaintainer;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.memory.MemoryIndexSynchronizer;
import org.example.agent.context.memory.PendingLongTermCandidates;
import org.example.agent.context.session.SessionMessageStore;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * {@code /memory} —— 记忆系统用户入口（part4.md §7.2 / §7.6 / §7.7）。
 *
 * <p>新形态（mid-term 删除结构化摘要字段后）：
 * <ul>
 *   <li>{@code /memory} 或 {@code /memory pending} —— 列出待确认的 long-term 候选条目。</li>
 *   <li>{@code /memory confirm id[,id...] [--topic=<slug>]} —— 接受候选，按主题归入对应主题文件；新主题则创建。</li>
 *   <li>{@code /memory reject id[,id...]} —— 拒绝候选。</li>
 *   <li>{@code /memory show} —— 显示 short-term / mid-term / long-term / memory_index 的当前快照。</li>
 *   <li>{@code /memory flush} —— 强制压缩当前窗口（mid-term 原子重写 + worklog 追加 [meta]）。</li>
 *   <li>{@code /memory tick} —— 强制 long-term 提取本 session 当前未读到的短期记忆。</li>
 * </ul>
 */
@Component
public class MemoryCommand implements SlashCommand {

    private final PendingLongTermCandidates pending;
    private final LongTermStore longTermStore;
    private final MemoryIndex memoryIndex;
    private final LongTermMaintainer longTermMaintainer;
    private final MemoryIndexSynchronizer indexSync;
    private final SessionMessageStore sessionStore;
    private final ConversationCompressor compressor;

    public MemoryCommand(PendingLongTermCandidates pending,
                         LongTermStore longTermStore,
                         MemoryIndex memoryIndex,
                         LongTermMaintainer longTermMaintainer,
                         MemoryIndexSynchronizer indexSync,
                         SessionMessageStore sessionStore,
                         ConversationCompressor compressor) {
        this.pending = pending;
        this.longTermStore = longTermStore;
        this.memoryIndex = memoryIndex;
        this.longTermMaintainer = longTermMaintainer;
        this.indexSync = indexSync;
        this.sessionStore = sessionStore;
        this.compressor = compressor;
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
            case "flush":   return flushCompress(sessionId, ctx);
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

        // worklog / mid-term 摘要
        if (sessionStore != null) {
            int worklogSize = sessionStore.worklogSize(sessionId);
            int midTermSize = (int) countMidTermRecords(sessionId);
            ctx.out().printf("  short-term (worklog): %d records (cap=%d)%n",
                    worklogSize, SessionMessageStore.WORKLOG_SOFT_CAP);
            ctx.out().printf("  mid-term   (window):  %d records%n", midTermSize);

            // 取最后 5 条 worklog
            List<SessionMessageStore.Record> recent = sessionStore.readWorklog(sessionId);
            int tail = Math.min(5, recent.size());
            if (tail > 0) {
                ctx.out().println("  worklog tail:");
                for (int i = recent.size() - tail; i < recent.size(); i++) {
                    SessionMessageStore.Record r = recent.get(i);
                    String preview = r.content == null ? "" : r.content.replace("\n", "\\n");
                    if (preview.length() > 100) preview = preview.substring(0, 100) + "...";
                    ctx.out().printf("    [%s] %s%n", r.role, preview);
                }
            }
        }

        java.util.List<LongTermStore.Topic> topics = longTermStore.loadTopics();
        ctx.out().printf("  long-term topics (%d):%n", topics.size());
        for (LongTermStore.Topic t : topics) {
            ctx.out().printf("    [%s] imp=%d pinned=%s entries=%d — %s%n",
                    t.getSeq() + "-" + t.getSlug(),
                    t.getImportance(), t.isPinned(), t.getEntryCount(),
                    t.getSummary().isEmpty() ? "(no summary)" : t.getSummary());
        }

        ctx.out().printf("  memory-index (%d entries):%n", memoryIndex.loadOrEmpty().size());
        for (MemoryIndex.IndexEntry e : memoryIndex.loadOrEmpty()) {
            ctx.out().printf("    %s — %s%n", e.getPath(), trim(e.getSummary()));
        }

        ctx.out().flush();
        return 0;
    }

    private long countMidTermRecords(String sessionId) {
        if (sessionStore == null) return 0L;
        // 通过 in-memory mid-term 渲染长度推断;实际值通过 renderMidTermForContext 文本行数估算
        String text = sessionStore.renderMidTermForContext(sessionId);
        if (text == null || text.isEmpty()) return 0L;
        // 行数 - 1 ([meta] 头);非 [meta] 的都算消息记录
        return text.split("\n\n").length;
    }

    /**
     * 强制压缩当前窗口 —— 触发与 {@code ContextBuilder.syncCompressMessages} 同等的写盘流程。
     * 用于用户希望立即把内存中的窗口压紧以节省后续装配 token 时手动调用。
     */
    private int flushCompress(String sessionId, CliContext ctx) {
        if (sessionStore == null || compressor == null) {
            ctx.out().println("flush: sessionStore or compressor not available");
            ctx.out().flush();
            return 1;
        }
        SessionMessageStore.Session s = sessionStore.getOrCreate(sessionId);
        List<Message> current = s.snapshot();
        long before = SessionMessageStore.estimateMessagesTokens(current);
        // 用一个宽松预算 (context window) 触发压缩,让 compressor 自己挑合适的轮数
        long budget = Math.max(1024L, before / 2);
        try {
            List<Message> compressed = compressor.loadMessages(current, null, budget, null);
            long after = SessionMessageStore.estimateMessagesTokens(compressed);
            // 写 worklog 元事件
            sessionStore.addMeta(sessionId,
                    "[manual-flush] " + before + "→" + after + " tokens (rounds=" + compressed.size() + ")");
            // 替换窗口 + 原子写 mid-term
            sessionStore.replaceWindow(sessionId, compressed,
                    SessionMessageStore.CompressionInfo.manualFlush(before, after, compressed.size()));
            ctx.out().printf("flush: mid-term recompressed — %d→%d tokens, %d records%n",
                    before, after, compressed.size());
        } catch (Exception ex) {
            ctx.out().printf("flush: failed — %s%n", ex.getMessage());
            ctx.out().flush();
            return 1;
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

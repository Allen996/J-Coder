package org.example.agent.context.memory;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每轮 mid-term 增量更新钩子（part4.md §7.2 "每轮对话结束后由轻量级 LLM 增量更新"）。
 *
 * <p>注册成 {@link ReActLoopObserver}，挂在 {@code onFinish} 上：
 * <ol>
 *   <li>读 session 全部消息 → 与上次 mid-term 的"快照"（即上次处理的 turn 数）对比。</li>
 *   <li>新的 turn 切片送给 {@link MemorySummarizer#summarizeMidTermTurn}。</li>
 *   <li>合并 patch 到既有 mid-term；空 patch 跳过写盘。</li>
 *   <li>写入 mid-term.json 并通过 {@link MemoryIndexSynchronizer} 同步 MEMORY.md。</li>
 * </ol>
 *
 * <p>同时暴露 {@link #regenerateForSession(String)}，由 {@code ReplLoop.dispatchAgent}
 * 在检测到 10 分钟空闲时调用，做 session 结束级整体重生成。
 */
@Slf4j
@Component
public class MemoryTurnHook {

    private final SessionMessageStore sessionStore;
    private final MemorySummarizer summarizer;
    private final MidTermStore midTermStore;
    private final MemoryIndexSynchronizer indexSync;

    private final Map<String, Integer> lastSummarizedTurns = new ConcurrentHashMap<>();

    public MemoryTurnHook(SessionMessageStore sessionStore,
                          MemorySummarizer summarizer,
                          MidTermStore midTermStore,
                          MemoryIndexSynchronizer indexSync) {
        this.sessionStore = sessionStore;
        this.summarizer = summarizer;
        this.midTermStore = midTermStore;
        this.indexSync = indexSync;
    }

    /**
     * 由 {@code AgentRuntimeImpl.finalize(...)} 在每轮执行结束后调用一次。
     * 触发单轮 mid-term 增量更新；LLM 不可用或 patch 为空时静默 no-op。
     */
    public void onTurnFinished(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            updateMidTermIncremental(sessionId);
        } catch (Exception ex) {
            log.warn("MemoryTurnHook onTurnFinished failed for session {}: {}", sessionId, ex.getMessage(), ex);
        }
    }

    /**
     * 单轮 mid-term 增量更新：把上次处理后新增的对话送给 LLM，patch 进既有 mid-term。
     */
    public boolean updateMidTermIncremental(String sessionId) {
        SessionMessageStore.Session session = sessionStore.get(sessionId);
        if (session == null) return false;
        List<Message> all = session.snapshot();
        int currentSize = all.size();
        int last = lastSummarizedTurns.getOrDefault(sessionId, 0);
        if (currentSize <= last) return false;

        // 取最近一轮：上一次游标到本次结尾（"最近一轮"语义）
        List<Message> newTurn = all.subList(Math.max(0, last - 1), currentSize);
        if (newTurn.isEmpty()) return false;

        MidTermStore.MidTerm previous = midTermStore.loadOrEmpty(sessionId);
        MemorySummarizer.MidTermPatch patch;
        try {
            patch = summarizer.summarizeMidTermTurn(sessionId, newTurn, previous);
        } catch (Exception ex) {
            log.warn("summarizer.summarizeMidTermTurn failed: {}", ex.getMessage());
            return false;
        }
        if (patch == null || patch.isEmpty()) {
            lastSummarizedTurns.put(sessionId, currentSize);
            return false;
        }
        MidTermStore.MidTerm merged = mergePatch(previous, patch, sessionId);
        midTermStore.updateMidTerm(sessionId, merged);
        indexSync.noteMidTermSession(sessionId);
        lastSummarizedTurns.put(sessionId, currentSize);
        log.info("MemoryTurnHook: session {} mid-term updated (done+{} inProgress+{} blocked+{} rules+{})",
                sessionId,
                patch.getDeltaCrossSessionDone().size(),
                patch.getDeltaCrossSessionInProgress().size(),
                patch.getDeltaCrossSessionBlocked().size(),
                patch.getDeltaContextualRules().size());
        return true;
    }

    /**
     * Session 结束级整体重生成（part4 §7.2 "会话结束时整体重生成"）。
     */
    public MidTermStore.MidTerm regenerateForSession(String sessionId) {
        SessionMessageStore.Session session = sessionStore.get(sessionId);
        if (session == null) return null;
        List<Message> all = session.snapshot();
        MidTermStore.MidTerm regenerated;
        try {
            regenerated = summarizer.regenerateMidTerm(sessionId, all);
        } catch (Exception ex) {
            log.warn("regenerateMidTerm failed: {}", ex.getMessage());
            return null;
        }
        if (regenerated == null) return null;
        midTermStore.updateMidTerm(sessionId, regenerated);
        indexSync.noteMidTermSession(sessionId);
        lastSummarizedTurns.put(sessionId, all.size());
        log.info("MemoryTurnHook: session {} mid-term regenerated", sessionId);
        return regenerated;
    }

    static MidTermStore.MidTerm mergePatch(MidTermStore.MidTerm prev,
                                           MemorySummarizer.MidTermPatch patch,
                                           String sessionId) {
        List<String> done = mergeList(prev == null ? List.of() : prev.getCrossSessionDone(),
                patch.getDeltaCrossSessionDone());
        List<String> inProgress = mergeList(prev == null ? List.of() : prev.getCrossSessionInProgress(),
                patch.getDeltaCrossSessionInProgress());
        List<String> blocked = mergeList(prev == null ? List.of() : prev.getCrossSessionBlocked(),
                patch.getDeltaCrossSessionBlocked());
        String sessionSummary = (patch.getDeltaSessionSummary() != null && !patch.getDeltaSessionSummary().isBlank())
                ? patch.getDeltaSessionSummary()
                : (prev == null ? "" : prev.getSessionSummary());
        List<String> userFocus = mergeList(prev == null ? List.of() : prev.getUserFocus(),
                patch.getDeltaUserFocus());
        List<String> rules = mergeList(prev == null ? List.of() : prev.getContextualRules(),
                patch.getDeltaContextualRules());
        return new MidTermStore.MidTerm(sessionId,
                done, inProgress, blocked,
                sessionSummary, userFocus, rules,
                prev == null ? "" : prev.getSummary(),
                prev == null ? new ArrayList<>() : prev.getTopics(),
                prev == null ? new ArrayList<>() : prev.getKeywords(),
                prev == null ? 3 : prev.getImportance(),
                java.time.Instant.now());
    }

    private static List<String> mergeList(List<String> existing, List<String> delta) {
        if (delta == null || delta.isEmpty()) return existing == null ? new ArrayList<>() : existing;
        List<String> out = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
        Set<String> seen = new HashSet<>();
        for (String s : out) seen.add(s.strip());
        for (String d : delta) {
            if (d == null || d.isBlank()) continue;
            String key = d.strip();
            if (seen.add(key)) out.add(d);
        }
        return out;
    }

    Map<String, Integer> lastSummarizedTurnsSnapshot() {
        return new HashMap<>(lastSummarizedTurns);
    }
}
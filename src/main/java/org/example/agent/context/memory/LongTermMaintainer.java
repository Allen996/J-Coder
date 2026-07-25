package org.example.agent.context.memory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 长期记忆的异步周期维护器（part4.md §7.2 + 用户澄清："long memo 提取异步进行,定期从短期记忆中进行总结"）。
 *
 * <p>每 {@link #intervalSeconds} 秒扫一遍已知 session 的短期记忆，
 * 把上次未处理过的最新消息交给 {@link MemorySummarizer#extractLongTermCandidates}，
 * 候选条目进入 {@link PendingLongTermCandidates} 等待 {@code /memory confirm} 人工 ack。
 *
 * <p>关键防抖：
 * <ul>
 *   <li>每个 session 记录上次已处理的 turn 数（{@code lastProcessedTurns}），避免重复消耗 LLM 配额。</li>
 *   <li>新增消息数低于阈值（默认 2 条 user 消息）则跳过本轮，降低噪声。</li>
 * </ul>
 *
 * <p>线程模型：单线程 {@link ScheduledExecutorService}；与 REPL 主线程并发。
 * 不阻塞主对话流；失败时 swallow + log。
 */
@Slf4j
@Component
public class LongTermMaintainer {

    /** 默认 60 秒一轮；可通过 {@code agent.memory.ltm-extract-interval-seconds} 覆盖。 */
    public static final int DEFAULT_INTERVAL_SECONDS = 60;
    /** 默认至少新增 2 条 user 消息才触发一次 LLM 调用；可通过 system property 覆盖。 */
    public static final int DEFAULT_MIN_NEW_USER_MESSAGES = 2;
    private static final String SHUTDOWN_NAME = "memory-longterm-maintainer";

    private final SessionMessageStore sessionStore;
    private final MemorySummarizer summarizer;
    private final PendingLongTermCandidates pending;
    private final MemoryIndexSynchronizer indexSync;

    private final int intervalSeconds;
    private final int minNewUserMessages;

    private final Map<String, Integer> lastProcessedTurns = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> totalProcessed = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeSessionId = new AtomicReference<>();

    private volatile ScheduledExecutorService executor;
    private volatile boolean running;

    @org.springframework.beans.factory.annotation.Autowired
    public LongTermMaintainer(SessionMessageStore sessionStore,
                              MemorySummarizer summarizer,
                              PendingLongTermCandidates pending,
                              MemoryIndexSynchronizer indexSync) {
        this(sessionStore, summarizer, pending, indexSync,
                Integer.getInteger("agent.memory.ltm-extract-interval-seconds", DEFAULT_INTERVAL_SECONDS),
                Integer.getInteger("agent.memory.ltm-min-new-turns", DEFAULT_MIN_NEW_USER_MESSAGES));
    }

    LongTermMaintainer(SessionMessageStore sessionStore,
                       MemorySummarizer summarizer,
                       PendingLongTermCandidates pending,
                       MemoryIndexSynchronizer indexSync,
                       int intervalSeconds,
                       int minNewUserMessages) {
        this.sessionStore = sessionStore;
        this.summarizer = summarizer;
        this.pending = pending;
        this.indexSync = indexSync;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.minNewUserMessages = Math.max(1, minNewUserMessages);
    }

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, SHUTDOWN_NAME);
            t.setDaemon(true);
            return t;
        });
        running = true;
        executor.scheduleAtFixedRate(this::safeTick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("LongTermMaintainer started: every {}s (min {} new user msg)",
                intervalSeconds, minNewUserMessages);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
        }
    }

    /** 单次扫描：供测试或主动触发使用（CLI 派发点也可调用）。 */
    public int tick() {
        return runTick();
    }

    private void safeTick() {
        try {
            runTick();
        } catch (Exception ex) {
            log.warn("LongTermMaintainer tick failed: {}", ex.getMessage(), ex);
        }
    }

    private int runTick() {
        String sessionId = activeSessionId.get();
        if (sessionId == null) return 0;
        return processSession(sessionId);
    }

    /** 设置当前活跃 session（每轮用户输入时由 AgentRuntime 或 CLI 调用）。 */
    public void setActiveSessionId(String sessionId) {
        activeSessionId.set(sessionId);
    }

    public String getActiveSessionId() {
        return activeSessionId.get();
    }

    /**
     * 处理单个 session 的最新增量。如果新增不足以触发,则跳过。
     * 返回本轮新增候选条目数量。
     */
    public int processSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        SessionMessageStore.Session session = sessionStore.get(sessionId);
        if (session == null) return 0;
        List<Message> all = session.snapshot();
        int currentSize = all.size();
        Integer last = lastProcessedTurns.get(sessionId);
        int lastCount = last == null ? 0 : last;
        if (currentSize <= lastCount) return 0;

        int newUserCount = countUserMessages(all.subList(lastCount, currentSize));
        if (newUserCount < minNewUserMessages && lastCount > 0) {
            return 0;
        }

        List<Message> slice = all.subList(lastCount, currentSize);
        List<MemorySummarizer.ExtractedCandidate> candidates;
        try {
            candidates = summarizer.extractLongTermCandidates(slice);
        } catch (Exception ex) {
            log.warn("LongTermMaintainer extract failed for session {}: {}", sessionId, ex.getMessage());
            return 0;
        }

        int added = 0;
        if (candidates != null && !candidates.isEmpty()) {
            List<PendingLongTermCandidates.Pending> addedItems = pending.add(candidates, sessionId);
            added = addedItems.size();
        }
        lastProcessedTurns.put(sessionId, currentSize);
        totalProcessed.computeIfAbsent(sessionId, k -> new AtomicInteger()).addAndGet(added);

        if (added > 0) {
            log.info("LongTermMaintainer: session {} produced {} candidate(s)", sessionId, added);
        }
        return added;
    }

    static int countUserMessages(List<Message> msgs) {
        int n = 0;
        for (Message m : msgs) {
            if (m instanceof org.springframework.ai.chat.messages.UserMessage) n++;
        }
        return n;
    }

    /** 重置一个 session 的处理游标（确认/拒绝候选条目后调用,避免无限累积）。 */
    public void resetCursor(String sessionId) {
        lastProcessedTurns.put(sessionId, 0);
    }

    public Map<String, Integer> lastProcessedSnapshot() {
        return Map.copyOf(lastProcessedTurns);
    }

    public boolean isRunning() {
        return running;
    }

    public Duration interval() {
        return Duration.ofSeconds(intervalSeconds);
    }
}

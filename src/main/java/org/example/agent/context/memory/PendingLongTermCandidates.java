package org.example.agent.context.memory;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 待确认 long-term 候选条目容器（part4.md §7.2 / §7.6 / §7.7 处理流程）。
 *
 * <p>来源：{@link LongTermMaintainer} 周期性从短期记忆中提取得到；
 * 消费：{@code /memory confirm [id]} 追加到对应主题文件，
 * {@code /memory reject [id]} 丢弃。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每条候选全局唯一 id（{@code cand-1}, {@code cand-2} ... 序号稳定便于 CLI 输入）。</li>
 *   <li>线程安全：{@link CopyOnWriteArrayList} + AtomicInteger 序号。</li>
 *   <li>不持久化 —— 进程重启清空；不影响落地在主题文件的事实。</li>
 *   <li>每条候选携带主题判定（{@code topic / topicProposal}）；用户可在确认时改写目标主题 slug。</li>
 * </ul>
 */
@Component
public class PendingLongTermCandidates {

    @Getter
    public static final class Pending {
        private final String id;
        private final LongTermStore.Category category;
        private final String title;
        private final String content;
        private final int importance;
        private final boolean pinned;
        private final String topic;
        private final String topicProposal;
        private final String evidence;
        private final String reason;
        private final String sourceSessionId;
        private final Instant addedAt;

        Pending(int ordinal, MemorySummarizer.ExtractedCandidate src, String sessionId) {
            this.id = "cand-" + ordinal;
            this.category = src.getCategory();
            this.title = src.getTitle();
            this.content = src.getContent();
            this.importance = src.getImportance();
            this.pinned = src.isPinned();
            this.topic = src.getTopic();
            this.topicProposal = src.getTopicProposal();
            this.evidence = src.getEvidence();
            this.reason = src.getReason();
            this.sourceSessionId = sessionId;
            this.addedAt = Instant.now();
        }

        /** @deprecated 保留兼容；CLI 不再展示。 */
        @Deprecated
        public boolean isDeprecated() { return false; }
    }

    private final List<Pending> items = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextOrdinal = new AtomicInteger(1);

    public synchronized List<Pending> add(List<MemorySummarizer.ExtractedCandidate> extracted, String sessionId) {
        List<Pending> added = new ArrayList<>();
        for (MemorySummarizer.ExtractedCandidate c : extracted) {
            if (c == null || c.getContent() == null || c.getContent().isBlank()) continue;
            Pending p = new Pending(nextOrdinal.getAndIncrement(), c, sessionId);
            items.add(p);
            added.add(p);
        }
        return added;
    }

    public synchronized List<Pending> snapshot() {
        return new ArrayList<>(items);
    }

    public synchronized Pending findById(String id) {
        if (id == null) return null;
        for (Pending p : items) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    public synchronized List<Pending> findByIds(List<String> ids) {
        List<Pending> matches = new ArrayList<>();
        for (String id : ids) {
            Pending p = findById(id);
            if (p != null) matches.add(p);
        }
        return matches;
    }

    public synchronized Pending remove(String id) {
        if (id == null) return null;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                return items.remove(i);
            }
        }
        return null;
    }

    public synchronized void clear() {
        items.clear();
        nextOrdinal.set(1);
    }

    public int size() {
        return items.size();
    }

    String newIdForTest() {
        return "cand-" + nextOrdinal.getAndIncrement();
    }

    Pending addForTest(MemorySummarizer.ExtractedCandidate src, String sessionId) {
        Pending p = new Pending(nextOrdinal.getAndIncrement(), src, sessionId);
        items.add(p);
        return p;
    }
}
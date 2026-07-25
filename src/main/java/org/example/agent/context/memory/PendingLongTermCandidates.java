package org.example.agent.context.memory;

import lombok.Getter;
import lombok.ToString;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 待确认 long-term 候选条目容器（part4.md §7.2 / §7.7 处理流程）。
 *
 * <p>来源：{@link LongTermMaintainer} 周期性从短期记忆中提取得到；
 * 消费：{@code /memory confirm [id]} 追加到 {@link LongTermStore}，
 * {@code /memory reject [id]} 丢弃。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每条候选全局唯一 id（{@code cand-1}, {@code cand-2} ... 序号稳定便于 CLI 输入）。</li>
 *   <li>线程安全：{@link CopyOnWriteArrayList} + AtomicInteger 序号。</li>
 *   <li>不持久化 —— 进程重启清空；不影响落地在 Nico.md 的事实。</li>
 * </ul>
 */
@Component
public class PendingLongTermCandidates {

    @Getter
    @ToString(of = {"id", "category", "content", "importance", "sourceSessionId", "addedAt"})
    public static final class Pending {
        private final String id;
        private final LongTermStore.Category category;
        private final String content;
        private final int importance;
        private final String evidence;
        private final String reason;
        private final String sourceSessionId;
        private final Instant addedAt;

        Pending(int ordinal, MemorySummarizer.ExtractedCandidate src, String sessionId) {
            this.id = "cand-" + ordinal;
            this.category = src.getCategory();
            this.content = src.getContent();
            this.importance = src.getImportance();
            this.evidence = src.getEvidence();
            this.reason = src.getReason();
            this.sourceSessionId = sessionId;
            this.addedAt = Instant.now();
        }
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

    /** 清空全部（CLI 调试用，正常情况不应自动调用）。 */
    public synchronized void clear() {
        items.clear();
        nextOrdinal.set(1);
    }

    public int size() {
        return items.size();
    }

    /** 给 entry 创建用的 id 自增桶，仅测试使用。 */
    String newIdForTest() {
        return "cand-" + nextOrdinal.getAndIncrement();
    }

    /** 测试用入口 —— 直接添加一个抽取条目（带 auto-id）。 */
    Pending addForTest(MemorySummarizer.ExtractedCandidate src, String sessionId) {
        Pending p = new Pending(nextOrdinal.getAndIncrement(), src, sessionId);
        items.add(p);
        return p;
    }
}

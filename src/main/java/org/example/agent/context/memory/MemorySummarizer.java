package org.example.agent.context.memory;

import lombok.Getter;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 长期记忆候选提取接口（part4.md §7.6）。
 *
 * <p>历史接口（{@code summarizeMidTermTurn} / {@code regenerateMidTerm}）已下线 —— mid-term 在
 * part4 §7.x 重设计后只服务当前 session,不再需要 LLM 摘要。具体实现走
 * {@link FlashMemorySummarizer#extractLongTermCandidates}。
 *
 * <p>§7.5 硬性约束：模型不可用或解析失败时返回 null / 空集合,由调用方 no-op,不写启发式兜底。
 */
public interface MemorySummarizer {

    /**
     * Part4 §7.6 长期记忆候选条目提取 —— 周期性从短期记忆中挖掘候选。
     *
     * @param unsummarizedTurns 待处理的消息切片（通常来自最新 N 轮）
     * @param existingTopics 已有主题列表（用于主题匹配）
     * @param existingTitles 已有候选标题列表（用于去重）
     */
    List<ExtractedCandidate> extractLongTermCandidates(List<Message> unsummarizedTurns,
                                                       List<String> existingTopics,
                                                       List<String> existingTitles);

    /** Part4 §7.6 候选 long-term 条目（含主题判定）。 */
    @Getter
    final class ExtractedCandidate {
        private final LongTermStore.Category category;
        private final String title;            // 候选标题（用于索引与人工核对）
        private final String content;
        private final int importance;
        private final boolean pinned;
        private final String topic;           // 命中已有主题的 slug/seq-slug；未命中则为 "NEW"
        private final String topicProposal;   // topic=="NEW" 时给出新主题短横线摘要
        private final String evidence;
        private final String reason;

        public ExtractedCandidate(LongTermStore.Category category, String title, String content,
                                  int importance, boolean pinned,
                                  String topic, String topicProposal,
                                  String evidence, String reason) {
            this.category = category == null ? LongTermStore.Category.FEEDBACK : category;
            this.title = title == null ? "" : title;
            this.content = content == null ? "" : content;
            this.importance = Math.max(1, Math.min(5, importance));
            this.pinned = pinned;
            this.topic = topic == null ? "NEW" : topic;
            this.topicProposal = topicProposal == null ? "" : topicProposal;
            this.evidence = evidence == null ? "" : evidence;
            this.reason = reason == null ? "" : reason;
        }

        /** 旧 5 字段构造器（迁移期使用）。 */
        public ExtractedCandidate(LongTermStore.Category category, String content,
                                  int importance, String evidence, String reason) {
            this(category, "", content, importance, false, "NEW", "", evidence, reason);
        }
    }
}

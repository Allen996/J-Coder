package org.example.agent.context.memory;

import lombok.Getter;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆系统的 LLM 摘要接口（part4.md §7.2 / §7.5 / §7.6）。
 *
 * <p>三种能力：
 * <ul>
 *   <li>{@link #summarizeMidTermTurn} —— 单轮对话 → 4 字段增量补丁；每轮调用一次。</li>
 *   <li>{@link #regenerateMidTerm} —— 整个 session 对话 → 完整 mid-term；session 结束时调用。</li>
 *   <li>{@link #extractLongTermCandidates} —— 多轮对话 → 候选 long-term 条目（含主题判定）；周期调用。</li>
 * </ul>
 *
 * <p>§7.5 硬性约束：实现不得在 LLM 不可用 / 输出解析失败时退回启发式兜底并落盘。
 * 失败时返回 null 或空列表；调用方据此 no-op，不写盘低质量替代品。
 */
public interface MemorySummarizer {

    /** Part4 §7.2 / §7.6 mid-term 增量更新 —— 从单轮对话中提取需要并入 mid-term 的新事实。 */
    MidTermPatch summarizeMidTermTurn(String sessionId, List<Message> turnMessages, MidTermStore.MidTerm previous);

    /** Part4 §7.2 / §7.6 mid-term 整体重生成 —— session 结束时调用。 */
    MidTermStore.MidTerm regenerateMidTerm(String sessionId, List<Message> allSessionMessages);

    /**
     * Part4 §7.6 long-term 候选条目提取 —— 周期性从短期记忆中挖掘候选。
     *
     * @param existingTopics 已有主题列表（用于主题匹配）
     * @param existingTitles 已有候选标题列表（用于去重）
     */
    List<ExtractedCandidate> extractLongTermCandidates(List<Message> unsummarizedTurns,
                                                      List<String> existingTopics,
                                                      List<String> existingTitles);

    /** Part4 §7.2 mid-term 增量补丁（4 字段）。 */
    @Getter
    final class MidTermPatch {
        private final List<String> deltaCrossSessionDone;
        private final List<String> deltaCrossSessionInProgress;
        private final List<String> deltaCrossSessionBlocked;
        /** 本轮新增的 sessionSummary 增量；null 表示无新增。 */
        private final String deltaSessionSummary;
        private final List<String> deltaUserFocus;
        private final List<String> deltaContextualRules;

        public MidTermPatch(List<String> deltaCrossSessionDone,
                            List<String> deltaCrossSessionInProgress,
                            List<String> deltaCrossSessionBlocked,
                            String deltaSessionSummary,
                            List<String> deltaUserFocus,
                            List<String> deltaContextualRules) {
            this.deltaCrossSessionDone = deltaCrossSessionDone == null ? new ArrayList<>() : deltaCrossSessionDone;
            this.deltaCrossSessionInProgress = deltaCrossSessionInProgress == null ? new ArrayList<>() : deltaCrossSessionInProgress;
            this.deltaCrossSessionBlocked = deltaCrossSessionBlocked == null ? new ArrayList<>() : deltaCrossSessionBlocked;
            this.deltaSessionSummary = deltaSessionSummary;
            this.deltaUserFocus = deltaUserFocus == null ? new ArrayList<>() : deltaUserFocus;
            this.deltaContextualRules = deltaContextualRules == null ? new ArrayList<>() : deltaContextualRules;
        }

        public boolean isEmpty() {
            return deltaCrossSessionDone.isEmpty()
                    && deltaCrossSessionInProgress.isEmpty()
                    && deltaCrossSessionBlocked.isEmpty()
                    && (deltaSessionSummary == null || deltaSessionSummary.isBlank())
                    && deltaUserFocus.isEmpty()
                    && deltaContextualRules.isEmpty();
        }

        /** 空 patch（无可合并内容）。 */
        public static MidTermPatch empty() {
            return new MidTermPatch(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    null, new ArrayList<>(), new ArrayList<>());
        }
    }

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
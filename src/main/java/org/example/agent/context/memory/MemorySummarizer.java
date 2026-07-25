package org.example.agent.context.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 记忆系统的 LLM 摘要接口（part4.md §7.2 + §7.7）。
 *
 * <p>三种能力：
 * <ul>
 *   <li>{@link #summarizeMidTermTurn} —— 单轮对话 → 5 字段增量补丁（合入既有 mid-term）；每轮调用一次。</li>
 *   <li>{@link #regenerateMidTerm} —— 整个 session 所有对话 → 5 字段完整 mid-term；session 结束时调用。</li>
 *   <li>{@link #extractLongTermCandidates} —— 多轮对话 → 候选 long-term 条目（§7.7 YAML 列表）；周期调用。</li>
 * </ul>
 *
 * <p>实现约定：失败时返回 null 或空列表，调用方降级到启发式实现。
 */
public interface MemorySummarizer {

    /** Part4 §7.2 / §7.6 mid-term 增量更新 —— 从单轮对话中提取需要并入 mid-term 的新事实。 */
    MidTermPatch summarizeMidTermTurn(String sessionId, List<Message> turnMessages, MidTermStore.MidTerm previous);

    /** Part4 §7.2 / §7.6 mid-term 整体重生成 —— session 结束时调用。 */
    MidTermStore.MidTerm regenerateMidTerm(String sessionId, List<Message> allSessionMessages);

    /** Part4 §7.7 long-term 候选条目提取 —— 周期性从短期记忆中挖掘候选。 */
    List<ExtractedCandidate> extractLongTermCandidates(List<Message> unsummarizedTurns);

    /** Part4 §7.2 mid-term 5 字段。 */
    @lombok.Getter
    @lombok.ToString
    final class MidTermPatch {
        private final String deltaCompleted;
        private final String deltaDecisions;
        private final String deltaLessons;
        private final String deltaPendingTodos;
        /** 非空时覆盖既有 session_goal。null 时保留。 */
        private final String newSessionGoal;

        public MidTermPatch(String deltaCompleted, String deltaDecisions, String deltaLessons,
                            String deltaPendingTodos, String newSessionGoal) {
            this.deltaCompleted = deltaCompleted == null ? "" : deltaCompleted;
            this.deltaDecisions = deltaDecisions == null ? "" : deltaDecisions;
            this.deltaLessons = deltaLessons == null ? "" : deltaLessons;
            this.deltaPendingTodos = deltaPendingTodos == null ? "" : deltaPendingTodos;
            this.newSessionGoal = newSessionGoal;
        }

        public boolean isEmpty() {
            return deltaCompleted.isBlank() && deltaDecisions.isBlank()
                    && deltaLessons.isBlank() && deltaPendingTodos.isBlank()
                    && (newSessionGoal == null || newSessionGoal.isBlank());
        }

        /** 空 patch（无可合并内容）。 */
        public static MidTermPatch empty() {
            return new MidTermPatch("", "", "", "", null);
        }
    }

    /** Part4 §7.7 候选 long-term 条目。 */
    @lombok.Getter
    @lombok.ToString
    final class ExtractedCandidate {
        private final LongTermStore.Category category;
        private final String content;
        private final int importance;
        private final String evidence;
        private final String reason;

        public ExtractedCandidate(LongTermStore.Category category, String content,
                                  int importance, String evidence, String reason) {
            this.category = category == null ? LongTermStore.Category.CONVENTION : category;
            this.content = content == null ? "" : content;
            this.importance = Math.max(1, Math.min(5, importance));
            this.evidence = evidence == null ? "" : evidence;
            this.reason = reason == null ? "" : reason;
        }
    }
}

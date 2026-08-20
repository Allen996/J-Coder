package org.example.agent.intent;

import java.util.List;

/**
 * LLM 意图分类器。设计稿 §3.1 + §5.4:用最便宜的模型档,prompt < 500 token,
 * 必须返回严格 JSON。
 *
 * <p>实现可以是任何 ChatModel(主模型降级使用);首期只暴露一次"快速分类"
 * 调用的能力,不参与多轮对话。
 */
public interface LlmIntentClassifier {

    /**
     * 对单条用户输入做一次 L1 分类。
     *
     * <p>失败语义(超时 / 解析错误 / 空响应)由实现自己翻译成
     * {@link Outcome#fallback(String)} 返回,不抛异常给上层。
     */
    Outcome classify(String executionId, String userInput);

    /** LLM 的原始输出 + 解析结果。 */
    record Outcome(
            IntentLabel primary,
            double confidence,
            List<L1IntentResult.Candidate> candidates,
            java.util.Map<String, Object> slots,
            List<String> negativeSignals,
            ModelRouteHint modelRouteHint,
            boolean degraded,
            String reason
    ) {
        public static Outcome fallback(String reason) {
            return new Outcome(null, 0.5, List.of(), java.util.Map.of(), List.of(),
                    ModelRouteHint.GENERAL, true, reason);
        }
    }
}
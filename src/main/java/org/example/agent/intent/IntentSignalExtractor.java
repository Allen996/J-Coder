package org.example.agent.intent;

import java.util.List;
import java.util.Map;

/**
 * 关键词/规则信号。设计稿 §5.1:cheap & fast,不参与最终打分,
 * 只在 LLM 选与规则严重不一致时强制下调置信度。
 */
public interface IntentSignalExtractor {

    SignalResult extract(String userInput);

    /**
     * @param keywordHits  每类命中的关键词列表,供日志展示
     * @param negativeSignals 反向信号(别 / 不要 / 只是 ...)
     * @param suggestedLabel 规则最倾向的标签(可能为 null —— 关键词无法判断时)
     */
    record SignalResult(
            IntentLabel suggestedLabel,
            double keywordMatchScore,
            List<String> keywordHits,
            List<String> negativeSignals,
            boolean ruleStrongConflict
    ) {}
}
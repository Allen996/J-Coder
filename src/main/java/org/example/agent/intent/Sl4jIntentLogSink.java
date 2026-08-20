package org.example.agent.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认 IntentLogSink:把 L1 / L2 决策打到 SLF4J INFO。
 * 设计稿 §8:"日志聚合进现有的 verify.log 体系"。
 */
@Component
public class Sl4jIntentLogSink implements IntentLogSink {

    private static final Logger log = LoggerFactory.getLogger("intent");

    @Override
    public void onL1(IntentGate.IntentLogEvent e) {
        if (!log.isInfoEnabled()) return;
        log.info("L1 exec={} tier={} label={} conf={} model={} rule={} fallback={} reason={} input={}",
                e.executionId(), e.tier(), e.finalLabel(), e.finalConfidence(),
                e.resolvedModel(), e.ruleSuggested(), e.fallback(), e.fallbackReason(),
                truncate(e.userInput(), 80));
    }

    @Override
    public void onL2(IntentGate.IntentLogEvent e) {
        if (!log.isInfoEnabled()) return;
        log.info("L2 exec={} label={} conf={} decision={} reason={}",
                e.executionId(), e.finalLabel(), e.finalConfidence(),
                e.tier(), e.fallbackReason());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
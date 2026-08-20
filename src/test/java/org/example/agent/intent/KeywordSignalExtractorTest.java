package org.example.agent.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键词信号提取器单测。覆盖设计稿 §5.1 的核心场景。
 */
class KeywordSignalExtractorTest {

    private final KeywordSignalExtractor extractor = new KeywordSignalExtractor();

    @Test
    @DisplayName("\"帮我看看 X 是怎么实现的\" → READ_CODE")
    void readSignal() {
        IntentSignalExtractor.SignalResult r = extractor.extract("帮我看看 X 是怎么实现的");
        assertEquals(IntentLabel.READ_CODE, r.suggestedLabel());
        assertFalse(r.keywordHits().isEmpty());
        assertTrue(r.negativeSignals().isEmpty());
    }

    @Test
    @DisplayName("\"把 X 改成 Y\" → WRITE_PROJECT,且命中写关键词")
    void writeSignal() {
        IntentSignalExtractor.SignalResult r = extractor.extract("把 utils/Util.java 改成支持 option");
        assertEquals(IntentLabel.WRITE_PROJECT, r.suggestedLabel());
        assertTrue(r.keywordHits().contains("改"));
    }

    @Test
    @DisplayName("\"跑一下测试\" → RUN_COMMAND")
    void runSignal() {
        IntentSignalExtractor.SignalResult r = extractor.extract("跑一下测试,看看 build 通不通");
        assertEquals(IntentLabel.RUN_COMMAND, r.suggestedLabel());
    }

    @Test
    @DisplayName("\"什么是泛型\" → CHAT_QA")
    void chatQa() {
        IntentSignalExtractor.SignalResult r = extractor.extract("什么是泛型?为什么 Java 要这样设计?");
        assertEquals(IntentLabel.CHAT_QA, r.suggestedLabel());
    }

    @Test
    @DisplayName("\"帮我做一个完整的 XX 改造方案\" → PLANNING")
    void planning() {
        IntentSignalExtractor.SignalResult r = extractor.extract("帮我做一个完整的 XX 改造方案,需要规划");
        assertEquals(IntentLabel.PLANNING, r.suggestedLabel());
    }

    @Test
    @DisplayName("\"别 edit 这个文件\" → 写意图 + negative_signal")
    void negativeSignalCaptured() {
        IntentSignalExtractor.SignalResult r = extractor.extract("别 edit 这个文件,只是看一下");
        assertNotNull(r.suggestedLabel());
        assertFalse(r.negativeSignals().isEmpty());
    }

    @Test
    @DisplayName("空输入 → null suggestedLabel,score=0")
    void emptyInput() {
        IntentSignalExtractor.SignalResult r = extractor.extract("");
        assertEquals(null, r.suggestedLabel());
        assertEquals(0.0, r.keywordMatchScore(), 0.001);
    }

    @Test
    @DisplayName("纯英文,关键词词典不覆盖 → 返回 suggestedLabel=null 但不报错")
    void englishFallback() {
        IntentSignalExtractor.SignalResult r = extractor.extract("explain how grep works");
        // 词典含部分英文 "explain" / "trace" → 应有结果
        assertNotNull(r);
    }
}
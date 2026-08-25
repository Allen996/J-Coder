package org.example.agent.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 中文优先的关键词信号提取器。设计稿 §5.1。
 *
 * <p><b>Baseline 版本</b>(用于 L1 调参 ground truth):与最初的实现对齐 —
 * 关键词纯 substring 命中、按命中数量 pickStrongest、无 per-word 权重。
 *
 * <p>只在负向词检测上做了一个 bug 修复:单字 "别" 必须处于"独立 token"位置
 * (前后不是汉字)才算负向信号,避免 "区别/差别/鉴别" 等概念词里的 "别" 误命中。
 * 这是原版会让 CHAT_QA 漏判的具体来源。
 *
 * <p>后续如果要在词典侧调优,必须用 golden.jsonl 的 train 上 grid-search,
 * dev 上选超参,test 上只报一次。
 */
@Component
public class KeywordSignalExtractor implements IntentSignalExtractor {

    private static final Map<IntentLabel, List<String>> KEYWORDS = new HashMap<>();
    static {
        KEYWORDS.put(IntentLabel.READ_CODE, List.of(
                "看", "看看", "看下", "看一下", "解释", "说明", "讲解", "介绍",
                "理解", "怎么实现", "怎么写", "怎么做的", "原理", "逻辑", "代码里",
                "实现", "阅读", "trace", "explain", "what does", "how does", "why"
        ));
        KEYWORDS.put(IntentLabel.WRITE_PROJECT, List.of(
                "改", "修改", "改一下", "写", "新增", "加上", "添加", "重构",
                "替换", "改成", "改为", "优化", "实现", "新增一个", "帮我加",
                "refactor", "implement", "add", "change", "fix"
        ));
        KEYWORDS.put(IntentLabel.RUN_COMMAND, List.of(
                "跑", "跑一下", "运行", "执行", "构建", "编译", "打包",
                "测试", "单测", "提交", "推送", "拉取", "merge", "rebase",
                "build", "test", "run", "mvn", "gradle", "npm", "git push",
                "git pull", "git commit"
        ));
        KEYWORDS.put(IntentLabel.CHAT_QA, List.of(
                "什么是", "为什么", "介绍下", "讲讲", "原理是", "区别", "对比",
                "what is", "why", "difference", "explain"
        ));
        KEYWORDS.put(IntentLabel.PLANNING, List.of(
                "规划", "方案", "改造方案", "完整", "整体", "拆解", "步骤",
                "plan", "design", "strategy"
        ));
    }

    private static final List<String> NEGATIVE_TOKENS = List.of(
            "不要", "先别", "千万别", "only", "just", "do not",
            "don't", "不要写", "不要改", "不要动"
    );

    /**
     * "别" 单独成词(前后不是汉字)才算负向信号,避免 "区别/差别/鉴别/特别" 误命中。
     */
    private static final Pattern STANDALONE_BIE = Pattern.compile(
            "(?<![\\u4e00-\\u9fff])别(?![\\u4e00-\\u9fff])"
    );

    private static final Set<String> WRITE_ONLY_MODIFIERS = new HashSet<>(Arrays.asList(
            "写", "改", "新增", "重构", "加上", "替换", "改成", "改为", "添加"
    ));

    @Override
    public SignalResult extract(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return new SignalResult(null, 0.0, List.of(), List.of(), false);
        }
        String lower = userInput.toLowerCase();

        Map<IntentLabel, List<String>> hits = new HashMap<>();
        for (Map.Entry<IntentLabel, List<String>> e : KEYWORDS.entrySet()) {
            List<String> matched = new ArrayList<>();
            for (String kw : e.getValue()) {
                if (lower.contains(kw.toLowerCase())) {
                    matched.add(kw);
                }
            }
            if (!matched.isEmpty()) hits.put(e.getKey(), matched);
        }

        List<String> negatives = new ArrayList<>();
        for (String neg : NEGATIVE_TOKENS) {
            if (lower.contains(neg.toLowerCase())) {
                negatives.add(neg);
            }
        }
        if (STANDALONE_BIE.matcher(userInput).find()) {
            negatives.add("别");
        }

        IntentLabel suggested = pickStrongest(hits);
        double score = scoreFor(suggested, hits);
        boolean conflict = false;

        return new SignalResult(suggested, score, flatten(hits), negatives, conflict);
    }

    private IntentLabel pickStrongest(Map<IntentLabel, List<String>> hits) {
        if (hits.isEmpty()) return null;
        IntentLabel best = null;
        int bestCount = 0;
        for (Map.Entry<IntentLabel, List<String>> e : hits.entrySet()) {
            if (e.getValue().size() > bestCount) {
                bestCount = e.getValue().size();
                best = e.getKey();
            }
        }
        return best;
    }

    private double scoreFor(IntentLabel suggested, Map<IntentLabel, List<String>> hits) {
        if (suggested == null) return 0.5;
        int total = hits.values().stream().mapToInt(List::size).sum();
        int match = hits.getOrDefault(suggested, List.of()).size();
        if (total == 0) return 0.5;
        return Math.min(1.0, 0.5 + 0.5 * ((double) match / total));
    }

    private List<String> flatten(Map<IntentLabel, List<String>> hits) {
        List<String> all = new ArrayList<>();
        for (List<String> v : hits.values()) all.addAll(v);
        return all;
    }

    public static Set<String> writeOnlyModifiers() {
        return WRITE_ONLY_MODIFIERS;
    }
}
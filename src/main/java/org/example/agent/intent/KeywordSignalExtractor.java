package org.example.agent.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 中文优先的关键词信号提取器。设计稿 §5.1。
 *
 * <p>每类标签一组关键词,命中越多 score 越高;同时扫反向前缀(别 / 不要 / 先别)。
 * 英文走降级路径:不做匹配,直接返回 suggestedLabel=null + score=0.5。
 *
 * <p>这层不参与最终打分,只给 scorer 提供辅助信号。
 */
@Component
public class KeywordSignalExtractor implements IntentSignalExtractor {

    /** 每类关键词(中文为主,英文为辅)。 */
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
            "别", "不要", "先别", "千万别", "only", "just", "do not",
            "don't", "不要写", "不要改", "不要动"
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

    /** 静态导出,供测试 / 文档使用。 */
    public static Set<String> writeOnlyModifiers() {
        return WRITE_ONLY_MODIFIERS;
    }
}
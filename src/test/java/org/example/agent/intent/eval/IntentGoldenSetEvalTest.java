package org.example.agent.intent.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.intent.CliIntentProperties;
import org.example.agent.intent.IntentContext;
import org.example.agent.intent.IntentGate;
import org.example.agent.intent.IntentLabel;
import org.example.agent.intent.IntentPrompter;
import org.example.agent.intent.IntentSignalExtractor;
import org.example.agent.intent.KeywordSignalExtractor;
import org.example.agent.intent.L1IntentResult;
import org.example.agent.intent.LlmConfidenceCalibrator;
import org.example.agent.intent.LlmIntentClassifier;
import org.example.agent.intent.LocalIntentScorer;
import org.example.agent.intent.ModelRouteHint;
import org.example.agent.intent.SlotCompletenessChecker;
import org.example.agent.intent.SlotCompletenessValidator;
import org.example.agent.intent.StrongPatternClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Intent Golden Set evaluator。
 *
 * <p>这是 <b>Calibration Harness</b>(校准工具),不是产品代码。它做的事:
 * <ol>
 *   <li>从 classpath 读 {@code eval/intent/golden.jsonl} 和 {@code golden.splits.json};</li>
 *   <li>按 split(train / dev / test)挑出待评测的样本;</li>
 *   <li>用 {@link FakeHeuristicLlmIntentClassifier} 模拟一次 LLM 分类 ——
 *       输出基于关键词层 + 槽位启发式的合理猜测;</li>
 *   <li>走完整 {@link IntentGate#classify} 流水线,产出与生产相同的 IntentContext;</li>
 *   <li>把每条 (id, input, gold, pred, llmConf, keywordScore, slotScore, conflict, negative, tier, modelRoute)
 *       落到 {@code eval/intent/results/intent-per-row-...jsonl};</li>
 *   <li>在内存里聚合 top-1 / macro-F1 / 混淆矩阵 / per-class precision+recall / fallback 率 / tier 分布
 *       并写到 {@code eval/intent/results/intent-summary-...json};</li>
 *   <li>如果设了 {@code intent.eval.gate.strict=true},top-1 < 85% 时 assert 失败(设计稿 §10)。</li>
 * </ol>
 *
 * <p><b>重要</b>:Test 上限只允许报一次最终数字。任何对权重/词典/prompt 的调整
 * 都必须在 train 上做,用 dev 选超参,然后只看一眼 test —— 这一 PR 完成后
 * 不应该再重跑 test。
 *
 * <p><b>真实 LLM 模式</b>:设 {@code intent.eval.real-llm=true} 且 classpath 上有 Spring AI 的
 * ChatModel bean 时,自动用真实 {@link org.example.agent.intent.ChatModelLlmIntentClassifier}
 * 替换 fake;否则保持 fake。Fake 不依赖网络,CI 跑得起。
 */
class IntentGoldenSetEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("L1 Golden Set: 跑完 300 条,产出 per-row 报告 + summary")
    void runGoldenSetEval() throws IOException {
        Path goldenPath = Path.of("eval/intent/golden.jsonl");
        Path splitsPath = Path.of("eval/intent/golden.splits.json");
        Path resultsDir = Path.of("eval/intent/results");
        Files.createDirectories(resultsDir);

        String split = System.getProperty("intent.eval.split", "test");
        boolean strict = Boolean.getBoolean("intent.eval.gate.strict");
        boolean useRealLlm = Boolean.getBoolean("intent.eval.real-llm");

        // 调参入口:从系统属性读 weights,默认回退到 CliIntentProperties 默认值
        // (wLlm=0.6 / wKeyword=0.2 / wSlot=0.2 / penConflict=0.15 / penNegative=0.10)。
        // grid search 脚本会通过 -Dintent.eval.wLlm=... -Dintent.eval.penConflict=... 注入。
        double wLlm = Double.parseDouble(System.getProperty("intent.eval.wLlm", "0.6"));
        double wKw = Double.parseDouble(System.getProperty("intent.eval.wKeyword", "0.2"));
        double wSlot = Double.parseDouble(System.getProperty("intent.eval.wSlot", "0.2"));
        double penC = Double.parseDouble(System.getProperty("intent.eval.penConflict", "0.15"));
        double penN = Double.parseDouble(System.getProperty("intent.eval.penNegative", "0.10"));

        // Load splits
        @SuppressWarnings("unchecked")
        Map<String, Object> splits = MAPPER.readValue(splitsPath.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) splits.get("counts");
        @SuppressWarnings("unchecked")
        Map<String, List<String>> splitMap = (Map<String, List<String>>) splits.get("splits");
        List<String> idsInSplit = splitMap.get(split);
        assertTrue(idsInSplit != null && !idsInSplit.isEmpty(),
                "split '" + split + "' has no ids");
        Map<String, Boolean> idSet = new HashMap<>();
        for (String id : idsInSplit) idSet.put(id, true);

        // Load properties — 把调参入口注入的 weights 写到 Scoring 节点
        // Model routing 也允许通过系统属性覆盖(默认 qwen3.7-flash / qwen3.7-plus),
        // 在 token-plan 等私人网关环境下需要切到支持的模型(典型 qwen3.8-max)。
        // 方案 B:calibration 阈值也支持系统属性覆盖,默认 6 个都启用。
        String lightModel = System.getProperty("intent.eval.model.light", "qwen3.7-flash");
        String codeModel  = System.getProperty("intent.eval.model.code",  "qwen3.7-plus");
        String generalModel = System.getProperty("intent.eval.model.general", "qwen3.7-plus");
        boolean calibEnabled = Boolean.parseBoolean(System.getProperty("intent.eval.calibration.enabled", "true"));
        double calHighClip   = Double.parseDouble(System.getProperty("intent.eval.cal.highClip", "0.85"));
        double calNegCap     = Double.parseDouble(System.getProperty("intent.eval.cal.negOverrideCap", "0.40"));
        double calConflictD  = Double.parseDouble(System.getProperty("intent.eval.cal.conflictDelta", "0.25"));
        double calConflictT  = Double.parseDouble(System.getProperty("intent.eval.cal.conflictRawThreshold", "0.70"));
        double calNoEvidence = Double.parseDouble(System.getProperty("intent.eval.cal.noEvidenceCap", "0.60"));
        double calFloor      = Double.parseDouble(System.getProperty("intent.eval.cal.floor", "0.05"));
        CliIntentProperties props = new CliIntentProperties(
                true,
                new CliIntentProperties.L1(true, 3000L, null, null,
                        new CliIntentProperties.Scoring(wLlm, wKw, wSlot, penC, penN),
                        new LlmConfidenceCalibrator.Calibration(
                                calibEnabled, calHighClip, calNegCap, calConflictD, calConflictT, calNoEvidence, calFloor)),
                null,
                new CliIntentProperties.ModelRouting(lightModel, codeModel, generalModel),
                null);

        // LLM — fake or real
        LlmIntentClassifier llm;
        if (useRealLlm) {
            ChatModel realChat = buildDashScopeChatModel(props.modelRouting().light());
            assert realChat != null : "REAL_LLM mode requires DASHSCOPE_API_KEY env var";
            llm = new org.example.agent.intent.ChatModelLlmIntentClassifier(
                    realChat, props, props.modelRouting().light());
        } else {
            llm = new FakeHeuristicLlmIntentClassifier(props);
        }

        // Build gate manually (避免 Spring context 启动开销;FakeLlm 不依赖 DI)
        KeywordSignalExtractor signals = new KeywordSignalExtractor();
        SlotCompletenessChecker slots = new SlotCompletenessChecker();
        SlotCompletenessValidator slotValidator = new SlotCompletenessValidator();
        LlmConfidenceCalibrator calibrator = new LlmConfidenceCalibrator(props.l1().calibration());
        LocalIntentScorer scorer = new LocalIntentScorer(props);
        IntentPrompter prompter = new IntentPrompter(
                new java.io.ByteArrayInputStream(new byte[0]),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8));
        prompter.setSuppress(true);
        IntentGate gate = new IntentGate(
                llm, signals, slots, slotValidator, calibrator, scorer, prompter,
                new org.example.agent.intent.IntentFallbackPolicy(props),
                props,
                new StrongPatternClassifier(),
                props.modelRouting().light(),
                props.modelRouting().code(),
                props.modelRouting().general(),
                List.of());

        // Iterate golden.jsonl
        List<RowReport> rows = new ArrayList<>();
        Map<String, String> idToGold = new LinkedHashMap<>();
        try (var br = Files.newBufferedReader(goldenPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> obj = MAPPER.readValue(line, Map.class);
                String id = (String) obj.get("id");
                if (!idSet.containsKey(id)) continue;
                String input = (String) obj.get("input");
                String gold = (String) obj.get("gold_label");
                idToGold.put(id, gold);

                long t0 = System.currentTimeMillis();
                IntentContext ctx;
                try {
                    ctx = gate.classify(id, input);
                } catch (Throwable t) {
                    ctx = null;
                }
                long dur = System.currentTimeMillis() - t0;

                RowReport r = new RowReport();
                r.id = id;
                r.input = input;
                r.gold_label = gold;
                r.is_offtopic_subset = false; // 第三阶段:OFF_TOPIC 已删除,所有非编程类样本归 CHAT_QA
                // 第三阶段:StrongPatternClassifier 反问路径观测
                r.strong_pattern_triggered = ctx != null && ctx.isClarifiedByStrongPattern();
                r.strong_pattern_types = ctx == null ? List.of()
                        : (ctx.strongPatternTypes() == null ? List.of() : ctx.strongPatternTypes());
                r.clarify_text = ctx == null ? null : ctx.clarifyText();
                if (ctx != null && ctx.l1() != null) {
                    r.pred_label = ctx.l1().primary() == null ? "PENDING" : ctx.l1().primary().name();
                    r.pred_conf = ctx.l1().confidence();
                    r.tier = ctx.tier().name();
                    r.fallback = ctx.l1().fallback();
                    r.fallback_reason = ctx.l1().fallbackReason();
                    r.llmConf = ctx.l1().confidence();
                    r.calibrated_conf = ctx.l1().confidence();
                    r.calibration_rules = ctx.l1().appliedCalibrationRules() == null
                            ? List.of() : ctx.l1().appliedCalibrationRules();
                    r.raw_llm_conf = ctx.l1().calibrationDiagnostics() == null
                            ? 0.0
                            : ctx.l1().calibrationDiagnostics().getOrDefault("raw", 0.0);
                    // 中间信号从 scorer 拿不到(已合并到 L1),但我们 rerun 一遍拿;
                    // 这条用来在报告里直接显示"为什么是 X"。
                    IntentSignalExtractor.SignalResult sig = signals.extract(input);
                    r.keyword_suggested = sig.suggestedLabel() == null ? null : sig.suggestedLabel().name();
                    r.keyword_score = sig.keywordMatchScore();
                    r.keyword_hits = sig.keywordHits();
                    r.negative_signals = sig.negativeSignals();
                    r.slot_score = slots.completeness(
                            ctx.l1().primary(),
                            ctx.l1().slots(),
                            input);
                    // 方案 B:slot 结构性校验结果(供后续聚合"slot 准入门槛是否生效"用)
                    SlotCompletenessValidator.ValidationResult vr = slotValidator.validate(
                            ctx.l1().primary(), ctx.l1().slots());
                    r.slot_validation_outcome = vr.describe();
                    // 方案 B:本条样本是否被 slot 准入门槛强制降级(tier != DIRECT 但 primary 是 WRITE/RUN
                    // 且 slot 校验失败)。这是一个可观测指标,不是断言。
                    r.tier_pinned_by_slot = !vr.allPassed()
                            && ("WRITE_PROJECT".equals(r.pred_label) || "RUN_COMMAND".equals(r.pred_label))
                            && !"DIRECT".equals(r.tier);
                } else {
                    r.pred_label = "ERROR";
                    r.pred_conf = 0.0;
                    r.tier = "ERROR";
                    r.fallback = true;
                    r.fallback_reason = "no ctx";
                    r.calibration_rules = List.of();
                    r.slot_validation_outcome = "SKIP";
                }
                r.duration_ms = dur;
                rows.add(r);
            }
        }

        // Write per-row report
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path perRowPath = resultsDir.resolve("intent-per-row-" + split + "-" + ts + ".jsonl");
        try (var w = new FileWriter(perRowPath.toFile(), StandardCharsets.UTF_8)) {
            for (RowReport r : rows) {
                w.write(MAPPER.writeValueAsString(r));
                w.write("\n");
            }
        }

        // Aggregate metrics
        Map<String, Object> summary = aggregate(rows);
        summary.put("generated_at", ts);
        summary.put("split", split);
        summary.put("n_rows", rows.size());
        summary.put("llm_mode", useRealLlm ? "REAL" : "FAKE_HEURISTIC");
        summary.put("scoring_weights", MAPPER.convertValue(props.l1().scoring(), Map.class));
        summary.put("wLlm", wLlm);
        summary.put("wKeyword", wKw);
        summary.put("wSlot", wSlot);
        summary.put("penConflict", penC);
        summary.put("penNegative", penN);
        summary.put("per_row_report", perRowPath.getFileName().toString());
        summary.put("gate_strict", strict);
        Path summaryPath = resultsDir.resolve("intent-summary-" + split + "-" + ts + ".json");
        Files.writeString(summaryPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));

        System.out.println("\n========== INTENT EVAL SUMMARY (" + split + ", " + summary.get("llm_mode") + ") ==========");
        System.out.println("rows:        " + summary.get("n_rows"));
        System.out.println("top1:        " + summary.get("top1_accuracy"));
        System.out.println("macro_f1:    " + summary.get("macro_f1"));
        System.out.println("fallback:    " + summary.get("fallback_rate"));
        System.out.println("tier_direct: " + summary.get("tier_direct"));
        System.out.println("tier_offer:  " + summary.get("tier_offer"));
        System.out.println("tier_clarify:" + summary.get("tier_clarify"));
        System.out.println("summary:     " + summaryPath);
        System.out.println("per-row:     " + perRowPath);

        if (strict) {
            double top1 = (double) summary.get("top1_accuracy");
            assertTrue(top1 >= 0.85, "top1 (" + top1 + ") < 0.85 design budget");
        }
    }

    /**
     * 把 per-row 报告聚合成 top-1 / macro-F1 / per-class 指标 / 混淆矩阵 / tier 分布。
     * 任何一行 pred_label 为 null / "PENDING" / "ERROR" 不计入 top1。
     */
    private Map<String, Object> aggregate(List<RowReport> rows) {
        int total = 0, correct = 0, fallback = 0;
        int tDirect = 0, tOffer = 0, tClarify = 0, tError = 0;
        Map<String, int[]> perClass = new TreeMap<>(); // [tp, fp, fn]
        for (String l : Arrays.asList("READ_CODE", "WRITE_PROJECT", "RUN_COMMAND",
                "CHAT_QA", "PLANNING")) {
            perClass.put(l, new int[]{0, 0, 0});
        }
        Map<String, Map<String, Integer>> confusion = new TreeMap<>();
        for (String l : perClass.keySet()) confusion.put(l, new TreeMap<>());

        // 校准规则触发统计(第三阶段:OFF_TOPIC 子集统计已删除——OFF_TOPIC 类别不存在)
        Map<String, Integer> rulesTriggered = new TreeMap<>(); // 4 个规则 + "NONE"
        int slotPinnedTotal = 0;
        // 方案 B 第二阶段修复:fallback 且 conf=0 → 视为"未决策",
        // 不计入 top-1 分子、不计入 per-class fn;
        // 仅计入 fallback_rate 分子(失败率统计)。
        int noDecisionCount = 0;
        // 第三阶段:StrongPatternClassifier 反问路径聚合
        int strongPatternTriggeredTotal = 0;
        Map<String, Integer> strongPatternByType = new TreeMap<>(); // GREETING/INJECTION/NEGATION → 命中数
        Map<String, Integer> strongPatternByGold = new TreeMap<>(); // gold_label → 触发数
        int strongPatternCorrected = 0; // 触发反问且 pred == gold 的样本数

        for (RowReport r : rows) {
            total++;
            String gold = r.gold_label;
            String pred = r.pred_label;
            if (pred == null || "PENDING".equals(pred) || "ERROR".equals(pred)) {
                tError++;
                continue;
            }
            if (r.fallback) fallback++;

            // 方案 B:no_decision 判定 —— LLM 失败但 conf=0 不是真决策
            boolean noDecision = r.fallback && r.pred_conf <= 0.0;
            if (noDecision) {
                noDecisionCount++;
                // 仍计 fallback_rate(失败率),但不参与 top-1 / per-class / OFF_TOPIC subset
                // 不计入 OFF_TOPIC 子集支持数(它不是真 OFF_TOPIC)
                continue;
            }

            switch (r.tier) {
                case "DIRECT" -> tDirect++;
                case "OFFER" -> tOffer++;
                case "CLARIFY" -> tClarify++;
            }
            // per-class: gold vs pred
            int[] row = perClass.get(gold);
            if (row == null) continue;
            if (pred.equals(gold)) {
                row[0]++; // tp
                correct++;
            } else {
                row[2]++; // fn
                // fp on predicted class
                int[] predRow = perClass.get(pred);
                if (predRow != null) predRow[1]++;
            }
            Map<String, Integer> confRow = confusion.get(gold);
            confRow.merge(pred, 1, Integer::sum);

            // 校准规则触发统计
            if (r.calibration_rules == null || r.calibration_rules.isEmpty()) {
                rulesTriggered.merge("NONE", 1, Integer::sum);
            } else {
                for (String rule : r.calibration_rules) {
                    rulesTriggered.merge(rule, 1, Integer::sum);
                }
            }

            // 方案 B:slot 准入门槛触发统计
            if (r.tier_pinned_by_slot) slotPinnedTotal++;

            // 第三阶段:强 pattern 反问路径统计
            if (r.strong_pattern_triggered) {
                strongPatternTriggeredTotal++;
                if (r.strong_pattern_types != null) {
                    for (String t : r.strong_pattern_types) {
                        strongPatternByType.merge(t, 1, Integer::sum);
                    }
                }
                strongPatternByGold.merge(gold, 1, Integer::sum);
                if (pred.equals(gold)) strongPatternCorrected++;
            }
        }
        // 方案 B:top-1 = 真决策中正确的比例(noDecision 已从分母中排除)
        double top1 = total == 0 ? 0.0 : (double) correct / (total - noDecisionCount);
        double macroF1 = 0.0;
        int n = 0;
        Map<String, Map<String, Double>> perClassOut = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : perClass.entrySet()) {
            int tp = e.getValue()[0], fp = e.getValue()[1], fn = e.getValue()[2];
            double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            macroF1 += f1;
            n++;
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("precision", round(precision));
            m.put("recall", round(recall));
            m.put("f1", round(f1));
            m.put("support", (double)(tp + fn));
            perClassOut.put(e.getKey(), m);
        }
        if (n > 0) macroF1 /= n;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("top1_accuracy", round(top1));
        out.put("macro_f1", round(macroF1));
        out.put("fallback_rate", round((double) fallback / total));
        out.put("tier_direct", tDirect);
        out.put("tier_offer", tOffer);
        out.put("tier_clarify", tClarify);
        out.put("tier_error", tError);
        out.put("per_class", perClassOut);
        // confusion matrix: row=gold, col=pred
        Map<String, Map<String, Integer>> compactConfusion = new LinkedHashMap<>();
        for (var e : confusion.entrySet()) {
            Map<String, Integer> pruned = new TreeMap<>();
            for (var inner : e.getValue().entrySet()) {
                if (inner.getValue() > 0) pruned.put(inner.getKey(), inner.getValue());
            }
            if (!pruned.isEmpty()) compactConfusion.put(e.getKey(), pruned);
        }
        out.put("confusion", compactConfusion);

        // 方案 B 第二阶段修复:报告 no_decision 数,便于观察 LLM 超时对评测的污染
        Map<String, Object> noDecisionStats = new LinkedHashMap<>();
        noDecisionStats.put("count", noDecisionCount);
        noDecisionStats.put("excluded_from_top1_denominator", true);
        out.put("no_decision_stats", noDecisionStats);

        // 第三阶段:OFF_TOPIC 子集已删除;校准规则 + slot 准入门槛 子指标
        out.put("calibration_rules_triggered", rulesTriggered);

        Map<String, Object> slotGate = new LinkedHashMap<>();
        slotGate.put("total_triggered", slotPinnedTotal);
        slotGate.put("total_write_run", countWriteRun(rows));
        out.put("slot_gate", slotGate);

        // 第三阶段:StrongPatternClassifier 反问路径聚合报告
        // 注:fake LLM 下 total_triggered 通常为 0 —— 因为 fake LLM 对反问类输入
        // (GREETING/INJECTION/NEGATION) fallback 到 null primary,而反问触发条件
        // 要求 llmOut.primary() ∈ {READ_CODE, WRITE_PROJECT, RUN_COMMAND, PLANNING}。
        // 真实 LLM 跑时这些字段才会有非零值(典型 CHAT_QA 样本被真实 LLM 判 CHAT_QA
        // primary 不触发,但边缘 case 如 "你好帮我看下 AuthService" 真实 LLM 判 READ_CODE
        // 会触发反问)。
        Map<String, Object> strongPatternStats = new LinkedHashMap<>();
        strongPatternStats.put("total_triggered", strongPatternTriggeredTotal);
        strongPatternStats.put("total_rows", rows.size());
        strongPatternStats.put("trigger_rate", rows.isEmpty() ? 0.0
                : round((double) strongPatternTriggeredTotal / rows.size()));
        strongPatternStats.put("by_type", strongPatternByType);
        strongPatternStats.put("by_gold_label", strongPatternByGold);
        strongPatternStats.put("correct_after_clarify", strongPatternCorrected);
        // 反问触发后 pred 是否仍是写读类(决策 B 保留 primary)
        int stillWriteRead = 0;
        for (RowReport r : rows) {
            if (r.strong_pattern_triggered
                    && ("READ_CODE".equals(r.pred_label) || "WRITE_PROJECT".equals(r.pred_label)
                            || "RUN_COMMAND".equals(r.pred_label) || "PLANNING".equals(r.pred_label))) {
                stillWriteRead++;
            }
        }
        strongPatternStats.put("still_write_read_primary", stillWriteRead);
        out.put("strong_pattern_stats", strongPatternStats);

        return out;
    }

    /** 统计 primary 是 WRITE_PROJECT 或 RUN_COMMAND 的样本数(用于 slot 准入门槛分母)。 */
    private static int countWriteRun(List<RowReport> rows) {
        int n = 0;
        for (RowReport r : rows) {
            if ("WRITE_PROJECT".equals(r.pred_label) || "RUN_COMMAND".equals(r.pred_label)) n++;
        }
        return n;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Per-row JSON record. */
    public static class RowReport {
        public String id;
        public String input;
        public String gold_label;
        public String pred_label;
        public double pred_conf;
        public String tier;
        public boolean fallback;
        public String fallback_reason;
        public double llmConf;
        public String keyword_suggested;
        public double keyword_score;
        public List<String> keyword_hits;
        public List<String> negative_signals;
        public double slot_score;
        public long duration_ms;
        // 方案 B 新增字段 ↓
        /** 校准前 LLM 原始 conf(来自 calibrationDiagnostics.raw)。 */
        public double raw_llm_conf;
        /** 校准后 conf(等于 pred_conf,因为 IntentGate 把 calibrated.value() 喂给 scorer)。 */
        public double calibrated_conf;
        /** 该样本实际触发的校准规则列表。 */
        public List<String> calibration_rules;
        /** slot 结构性校验结果:"PASS" / "MISSING:target_file" / "INVALID:action" 等。 */
        public String slot_validation_outcome;
        /** 是否被 slot 准入门槛强制降到非 DIRECT。 */
        public boolean tier_pinned_by_slot;
        /** 第三阶段:OFF_TOPIC 已删除,字段保留但恒为 false。 */
        public boolean is_offtopic_subset;
        // 第三阶段:StrongPatternClassifier 反问路径可观测字段
        /** 本条样本是否触发了强 pattern 反问(GREETING/INJECTION/NEGATION 任一命中且 LLM primary ∈ 写读类)。 */
        public boolean strong_pattern_triggered;
        /** 命中的强 pattern 类型列表(GREETING / INJECTION / NEGATION),可能为空。 */
        public List<String> strong_pattern_types;
        /** 反问文本(模板化中文);null 表示未触发反问。 */
        public String clarify_text;
    }

    /**
     * 离线/无网络的"假 LLM":<b>最小版本</b>(基线调参 ground truth)。
     *
     * <p>与上一版的复杂启发式不同,这个 fake 完全回退到"接受关键词层的 suggestedLabel 作为
     * primary,把 conf 设到 keywordMatchScore"。这是调参 baseline —— 任何"权重 + 词典"
     * 调整的效果都从这个基线上观察,不会再有启发式帮忙。
     *
     * <p>仍然模拟典型 LLM 的轻微噪声:加一个 second candidate(主类的邻类)用来观察
     * candidates 合并逻辑。
     */
    public static class FakeHeuristicLlmIntentClassifier implements LlmIntentClassifier {
        private final CliIntentProperties props;

        public FakeHeuristicLlmIntentClassifier(CliIntentProperties props) {
            this.props = props;
        }

        @Override
        public Outcome classify(String executionId, String userInput) {
            if (userInput == null || userInput.isBlank()) {
                return Outcome.fallback("empty input");
            }
            KeywordSignalExtractor k = new KeywordSignalExtractor();
            IntentSignalExtractor.SignalResult sig = k.extract(userInput);
            if (sig.suggestedLabel() == null) {
                return Outcome.fallback("no keyword hit");
            }
            IntentLabel primary = sig.suggestedLabel();
            IntentLabel second = neighbour(primary);
            double conf = sig.keywordMatchScore();
            List<L1IntentResult.Candidate> cands = List.of(
                    new L1IntentResult.Candidate(primary, conf),
                    new L1IntentResult.Candidate(second, Math.max(0.2, conf - 0.3))
            );
            Map<String, Object> slots = Map.of();
            ModelRouteHint hint = primary == IntentLabel.WRITE_PROJECT
                    ? ModelRouteHint.CODE
                    : (primary == IntentLabel.PLANNING ? ModelRouteHint.CODE : ModelRouteHint.LIGHT);
            return new Outcome(primary, conf, cands, slots,
                    sig.negativeSignals(), hint, false, null);
        }

        private IntentLabel neighbour(IntentLabel l) {
            return switch (l) {
                case READ_CODE -> IntentLabel.WRITE_PROJECT;
                case WRITE_PROJECT -> IntentLabel.READ_CODE;
                case RUN_COMMAND -> IntentLabel.WRITE_PROJECT;
                case CHAT_QA -> IntentLabel.READ_CODE;
                case PLANNING -> IntentLabel.WRITE_PROJECT;
            };
        }
    }

    /**
     * 真实 DashScope ChatModel。复刻 {@code LightweightChatModelConfig.memoryChatModel}
     * 的构造方式,但不依赖 Spring DI — 这样我们可以在 JUnit 测试里直接 new 出来跑真实评测。
     *
     * <p>读取 {@code DASHSCOPE_API_KEY} 环境变量;若不存在则返回 null,让上层决定怎么处理
     * (FAIL 或 fallback 到 fake)。
     */
    static ChatModel buildDashScopeChatModel(String modelName) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = readApiKeyFromAppYml();
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[REAL_LLM] No DASHSCOPE_API_KEY env var and no key in application.yml; cannot build ChatModel");
            return null;
        }
        System.err.println("[REAL_LLM] Building DashScope ChatModel: model=" + modelName
                + ", apiKey=" + (apiKey.length() > 8 ? apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4) : apiKey));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        RestClient.Builder restBuilder = RestClient.builder().requestFactory(factory);
        DashScopeApi.Builder apiBuilder = DashScopeApi.builder()
                .apiKey(apiKey)
                .restClientBuilder(restBuilder);
        String baseUrl = readBaseUrlFromEnv();
        if (baseUrl != null && !baseUrl.isBlank()) {
            System.err.println("[REAL_LLM] Using base-url: " + baseUrl);
            apiBuilder.baseUrl(baseUrl);
        } else {
            System.err.println("[REAL_LLM] No DASHSCOPE_BASE_URL; using SDK default (https://dashscope.aliyuncs.com)");
        }
        DashScopeApi api = apiBuilder.build();
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(modelName)
                .withMultiModel(true)
                .build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();
    }

    /**
     * 从 src/main/resources/application.yml 读 spring.ai.dashscope.api-key。
     * 仅作为"环境里没设 key"时的最后 fallback —— 测试进程退出后 key 不会持久化到任何地方。
     * <b>不会</b>把 key 写入任何新文件,只在进程内存里用。
     */
    private static String readApiKeyFromAppYml() {
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.exists(yml)) return null;
        try {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("api-key:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.startsWith("${")) {
                        int colon = value.indexOf(":", 2);
                        int close = value.indexOf("}", colon);
                        if (colon > 0 && close > colon) {
                            return value.substring(colon + 1, close).trim();
                        }
                    } else {
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * 读取 base-url,优先级:
     *   1) DASHSCOPE_BASE_URL 环境变量
     *   2) application.yml 里的 spring.ai.dashscope.base-url 占位符
     *   3) null(让 SDK 用默认 dashscope.aliyuncs.com)
     * <p>不显式传 base-url 会落到官方端点,导致私人网关 / 兼容层完全失效
     * (与 {@code LightweightChatModelConfig} 注释里描述的同一个坑)。
     */
    private static String readBaseUrlFromEnv() {
        String fromEnv = System.getenv("DASHSCOPE_BASE_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        Path yml = Path.of("src/main/resources/application.yml");
        if (!Files.exists(yml)) return null;
        try {
            for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.startsWith("base-url:")) {
                    String value = t.substring(t.indexOf(':') + 1).trim();
                    if (value.startsWith("${")) {
                        int colon = value.indexOf(":", 2);
                        int close = value.indexOf("}", colon);
                        if (colon > 0 && close > colon) {
                            return value.substring(colon + 1, close).trim();
                        }
                    } else {
                        return value;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
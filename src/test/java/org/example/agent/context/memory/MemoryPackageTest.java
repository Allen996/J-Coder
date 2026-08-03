package org.example.agent.context.memory;

import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆系统 package 的核心组件测试（part4.md §7.2 / §7.4 / §7.5 / §7.7）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link MidTermStore} —— 4 字段 JSON round-trip + 解析</li>
 *   <li>{@link LongTermStore} —— 多主题文件追加 / 持久化 / 解析</li>
 *   <li>{@link MemoryIndex} —— 目录页去重 / 匹配 topN 兼容</li>
 *   <li>{@link MemoryIndexSynchronizer} —— 启动同步 + 重复注册幂等</li>
 *   <li>{@link PendingLongTermCandidates} —— confirm / reject 不影响其他候选</li>
 *   <li>{@link FlashMemorySummarizer} —— 模型不可用 → no-op（无启发式兜底） + JSON/YAML 解析</li>
 *   <li>{@link LongTermMaintainer} —— 不会重复消费同样的 turns</li>
 *   <li>{@link MemoryRecallScorer} —— 评分门控 + 允许为空</li>
 * </ul>
 */
class MemoryPackageTest {

    @TempDir
    Path tempDir;

    private LongTermStore longTermStore;
    private MidTermStore midTermStore;
    private MemoryIndex memoryIndex;
    private TaskPlanRepository taskPlanRepository;

    @BeforeEach
    void setUp() throws Exception {
        longTermStore = new LongTermStore(tempDir);
        memoryIndex = new MemoryIndex(tempDir.resolve("MEMORY.md"));
        midTermStore = new MidTermStore(tempDir.resolve(".agent/sessions"));
        taskPlanRepository = new TaskPlanRepository(tempDir.resolve(".agent/tasks"));
        taskPlanRepository.init();
    }

    @AfterEach
    void tearDown() {
        // tempDir cleanup is automatic
    }

    // ============ MidTermStore ============

    @Test
    void midTermRoundTripPreservesAll4Fields() {
        String sessionId = "s-2026-08-02-001";
        MidTermStore.MidTerm mt = new MidTermStore.MidTerm(
                sessionId,
                List.of("实现 ShortTermStore"),
                List.of("实现 MidTermStore"),
                List.of("等 LongTermEntryStore"),
                "本次 session 重点是把记忆系统改造成 JSON 存储",
                List.of("召回准确率"),
                List.of("本轮只改设计"),
                "重构记忆系统存储形式",
                List.of("记忆系统", "上下文装配"),
                List.of("memory", "contextbuilder"),
                4,
                java.time.Instant.parse("2026-08-02T10:00:00Z"));

        midTermStore.updateMidTerm(sessionId, mt);

        MidTermStore.MidTerm loaded = midTermStore.loadOrEmpty(sessionId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSessionId()).isEqualTo(sessionId);
        assertThat(loaded.getSessionSummary()).contains("JSON");
        assertThat(loaded.getCrossSessionDone()).anyMatch(s -> s.contains("ShortTermStore"));
        assertThat(loaded.getCrossSessionInProgress()).anyMatch(s -> s.contains("MidTermStore"));
        assertThat(loaded.getCrossSessionBlocked()).anyMatch(s -> s.contains("LongTermEntryStore"));
        assertThat(loaded.getUserFocus()).contains("召回准确率");
        assertThat(loaded.getContextualRules()).contains("本轮只改设计");
        assertThat(loaded.getSummary()).contains("重构");
        assertThat(loaded.getImportance()).isEqualTo(4);
    }

    @Test
    void midTermHandlesMissingFileGracefully() {
        MidTermStore.MidTerm mt = midTermStore.loadOrEmpty("nonexistent");
        assertThat(mt).isNull();
    }

    // ============ LongTermStore (multi-topic files) ============

    @Test
    void longTermAppendPersistsAcrossReload() {
        LongTermStore.Topic topic = longTermStore.createTopic("spring-config",
                "Spring 配置约定", List.of("Spring"), List.of("spring", "dashscope"), 4, false);

        longTermStore.appendToTopic(topic, new LongTermStore.Entry(
                "", LongTermStore.Category.FEEDBACK, "不要修改生成的 entity", 5, true,
                "user: 不能改", "违反会导致 schema 不一致", topic.getSlug(),
                java.time.Instant.now(), false));
        longTermStore.appendToTopic(topic, new LongTermStore.Entry(
                "", LongTermStore.Category.PROJECT, "短期用最近5轮递减", 4, false,
                "user: 改", "对齐 part4", topic.getSlug(),
                java.time.Instant.now(), false));

        LongTermStore reloaded = new LongTermStore(tempDir);
        List<LongTermStore.Topic> topics = reloaded.loadTopics();
        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).getSlug()).isEqualTo("spring-config");
        assertThat(topics.get(0).getEntries()).hasSize(2);
        assertThat(topics.get(0).getEntries().stream()
                .anyMatch(e -> e.getCategory() == LongTermStore.Category.FEEDBACK
                        && e.getContent().contains("entity"))).isTrue();
    }

    @Test
    void longTermNewTopicGetsSequentialNumber() {
        LongTermStore.Topic t1 = longTermStore.createTopic("first", "1", List.of(), List.of(), 3, false);
        LongTermStore.Topic t2 = longTermStore.createTopic("second", "2", List.of(), List.of(), 3, false);
        LongTermStore.Topic t3 = longTermStore.createTopic("third", "3", List.of(), List.of(), 3, false);
        assertThat(t1.getSeq()).isEqualTo("001");
        assertThat(t2.getSeq()).isEqualTo("002");
        assertThat(t3.getSeq()).isEqualTo("003");
    }

    // ============ MemoryIndex ============

    @Test
    void memoryIndexMatchTopNReturnsRelevantEntries() {
        MemoryIndex idx = new MemoryIndex(tempDir.resolve("MEMORY.md"));
        idx.add("001-spring-config.md", "Spring AI 与 DashScope 配置约定");
        idx.add(".agent/sessions/abc/short-term.json", "会话 abc 短期对话流");
        idx.add(".agent/sessions/abc/mid-term.json", "会话 abc 中期摘要");
        idx.add(".agent/sessions/xyz/short-term.json", "会话 xyz 短期对话流");

        List<MemoryIndex.IndexEntry> hits = idx.matchTopN("abc", 5);
        assertThat(hits).hasSizeGreaterThanOrEqualTo(2);
        assertThat(hits).allMatch(e -> e.getPath().contains("abc") || e.getSummary().contains("abc"));
    }

    @Test
    void memoryIndexIsIdempotentOnReAdd() {
        MemoryIndex idx = new MemoryIndex(tempDir.resolve("MEMORY.md"));
        idx.add("001-spring-config.md", "Spring 配置");
        idx.add("001-spring-config.md", "Spring 配置");
        idx.add("001-spring-config.md", "Spring 配置");

        assertThat(idx.loadOrEmpty()).hasSize(1);
        assertThat(idx.loadOrEmpty().get(0).getPath()).isEqualTo("001-spring-config.md");
    }

    @Test
    void memoryIndexNoLruEviction() {
        MemoryIndex idx = new MemoryIndex(tempDir.resolve("MEMORY.md"));
        for (int i = 0; i < 25; i++) {
            idx.add(String.format("%03d-file-%d.md", i, i), "entry " + i);
        }
        // §7.2 LRU 取消：全部保留
        assertThat(idx.loadOrEmpty()).hasSize(25);
    }

    // ============ PendingLongTermCandidates ============

    @Test
    void pendingConfirmRemovesAndRejectKeepsOtherIntact() {
        PendingLongTermCandidates pending = new PendingLongTermCandidates();
        List<MemorySummarizer.ExtractedCandidate> cands = List.of(
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.FEEDBACK,
                        "禁止直接改 entity",
                        "禁止直接改 entity", 5, true, "001-spring-config", "",
                        "user: 不能改", "违反会有 schema 风险"),
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.FEEDBACK,
                        "公共 API 必有单测",
                        "公共 API 必有单测", 4, false, "NEW", "testing-conventions",
                        "user: 加测试", "团队基本约定"),
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.PROJECT,
                        "短期用最近5轮",
                        "短期用最近5轮", 5, true, "002-context-assembly", "",
                        "user: 改语义", "避免滑窗淘汰"));

        List<PendingLongTermCandidates.Pending> added = pending.add(cands, "s1");
        assertThat(added).hasSize(3);
        String firstId = added.get(0).getId();

        PendingLongTermCandidates.Pending confirmed = pending.remove(firstId);
        assertThat(confirmed).isNotNull();
        assertThat(pending.size()).isEqualTo(2);
    }

    @Test
    void pendingIgnoresEmptyContent() {
        PendingLongTermCandidates pending = new PendingLongTermCandidates();
        List<MemorySummarizer.ExtractedCandidate> cands = List.of(
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.FEEDBACK,
                        "", "", 5, false, "NEW", "", "", ""));
        assertThat(pending.add(cands, "s1")).isEmpty();
        assertThat(pending.size()).isEqualTo(0);
    }

    // ============ MemoryIndexSynchronizer ============

    @Test
    void memoryIndexSynchronizerIsIdempotent() throws Exception {
        Path sessionsRoot = tempDir.resolve(".agent/sessions");
        Files.createDirectories(sessionsRoot.resolve("s-1"));
        Files.writeString(sessionsRoot.resolve("s-1/short-term.json"), "{}");
        Files.writeString(sessionsRoot.resolve("s-1/mid-term.json"), "{}");

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, midTermStore, null, taskPlanRepository);
        sync.syncKnownPaths();
        int sizeAfterFirst = memoryIndex.loadOrEmpty().size();

        sync.syncKnownPaths();
        assertThat(memoryIndex.loadOrEmpty().size()).isEqualTo(sizeAfterFirst);
        assertThat(memoryIndex.loadOrEmpty().stream()
                .anyMatch(e -> e.getPath().equals(".agent/sessions/s-1/mid-term.json"))).isTrue();
        assertThat(memoryIndex.loadOrEmpty().stream()
                .anyMatch(e -> e.getPath().equals(".agent/sessions/s-1/short-term.json"))).isTrue();
    }

    @Test
    void memoryIndexSynchronizerTracksTaskPlanLifecycle() throws Exception {
        TaskPlan plan = TaskPlan.builder()
                .planId("plan-1")
                .goal("implement feature")
                .sessionId("s-1")
                .status(TaskPlanStatus.ACTIVE)
                .subtaskIds(List.of())
                .edges(List.of())
                .build();
        taskPlanRepository.savePlan(plan);

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, midTermStore, null, taskPlanRepository);
        sync.notePlan(plan.getPlanId());

        assertThat(memoryIndex.loadOrEmpty()).anyMatch(e ->
                e.getPath().equals(".agent/tasks/plan-1/plan.json"));
        assertThat(Files.readString(memoryIndex.path()))
                .contains("plan-1 任务计划");

        sync.removePlan(plan.getPlanId());

        assertThat(memoryIndex.loadOrEmpty()).noneMatch(e ->
                e.getPath().equals(".agent/tasks/plan-1/plan.json"));
        assertThat(Files.readString(memoryIndex.path()))
                .doesNotContain("plan-1 任务计划");
    }

    @Test
    void memoryIndexSynchronizerScansExistingActivePlanWithoutSessions() {
        TaskPlan plan = TaskPlan.builder()
                .planId("plan-existing")
                .goal("recover task")
                .sessionId("s-2")
                .status(TaskPlanStatus.ACTIVE)
                .subtaskIds(List.of())
                .edges(List.of())
                .build();
        taskPlanRepository.savePlan(plan);

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, midTermStore, null, taskPlanRepository);
        sync.syncKnownPaths();

        assertThat(memoryIndex.loadOrEmpty()).anyMatch(e ->
                e.getPath().equals(".agent/tasks/plan-existing/plan.json"));
    }

    @Test
    void memoryIndexSynchronizerTracksLongTermTopics() {
        longTermStore.createTopic("spring-config", "Spring 配置约定", List.of(), List.of(), 4, false);

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, midTermStore, null, taskPlanRepository);
        sync.noteLongTermTopics();

        assertThat(memoryIndex.loadOrEmpty()).anyMatch(e ->
                e.getPath().equals("001-spring-config.md"));
    }

    @Test
    void flashSummarizerReturnsNullWhenNoModel() {
        // §7.5：无模型时必须 no-op，不允许启发式兜底
        FlashMemorySummarizer summarizer = new FlashMemorySummarizer(null, null);
        List<org.springframework.ai.chat.messages.Message> empty = List.of();
        MemorySummarizer.MidTermPatch patch = summarizer.summarizeMidTermTurn("s1", empty, null);
        assertThat(patch).isNull();

        List<org.springframework.ai.chat.messages.Message> msgs = List.of(
                new org.springframework.ai.chat.messages.UserMessage("用户问 X 是怎么实现的？"));
        MemorySummarizer.MidTermPatch patch2 = summarizer.summarizeMidTermTurn("s1", msgs, null);
        assertThat(patch2).isNull();
    }

    @Test
    void flashSummarizerParsesYmlCandidateList() {
        String yaml = """
                - category: feedback
                  title: 记忆模型隔离
                  content: 不要修改生成的 entity 类
                  topic: 001-spring-config
                  importance: 5
                  pinned: true
                  evidence: user: 不能改 entity
                  reason: 违反会导致 schema 不一致
                - category: feedback
                  title: 单测约定
                  content: 所有公共 API 都要有单测
                  topic: NEW
                  topicProposal: testing-conventions
                  importance: 4
                  pinned: false
                  evidence: user: 加测试
                  reason: 团队约定
                """;

        List<MemorySummarizer.ExtractedCandidate> parsed = FlashMemorySummarizer.parseCandidatesYaml(yaml);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getCategory()).isEqualTo(LongTermStore.Category.FEEDBACK);
        assertThat(parsed.get(0).getImportance()).isEqualTo(5);
        assertThat(parsed.get(0).isPinned()).isTrue();
        assertThat(parsed.get(0).getTopic()).isEqualTo("001-spring-config");
        assertThat(parsed.get(1).getTopic()).isEqualTo("NEW");
        assertThat(parsed.get(1).getTopicProposal()).isEqualTo("testing-conventions");
    }

    @Test
    void flashSummarizerParsesMidTermJsonPatch() {
        String json = """
                {
                  "crossSessionProgress": {
                    "done": ["写完 §7.2"],
                    "inProgress": ["实现 §7.5"],
                    "blocked": []
                  },
                  "sessionSummary": "本轮重点是把 §7.5 落进代码",
                  "userFocus": ["召回准确率", "不要盲目注入"],
                  "contextualRules": ["本轮只改设计"]
                }
                """;
        MemorySummarizer.MidTermPatch patch = FlashMemorySummarizer.parseMidTermPatch(json);
        assertThat(patch).isNotNull();
        assertThat(patch.getDeltaCrossSessionDone()).contains("写完 §7.2");
        assertThat(patch.getDeltaCrossSessionInProgress()).contains("实现 §7.5");
        assertThat(patch.getDeltaSessionSummary()).contains("§7.5");
        assertThat(patch.getDeltaUserFocus()).contains("召回准确率");
        assertThat(patch.getDeltaContextualRules()).contains("本轮只改设计");
    }

    @Test
    void flashSummarizerHandlesNoneJsonGracefully() {
        String raw = "this is not json";
        MemorySummarizer.MidTermPatch patch = FlashMemorySummarizer.parseMidTermPatch(raw);
        assertThat(patch).isNull();
    }

    // ============ LongTermMaintainer ============

    @Test
    void maintainerSkipsIfNoNewUserMessages() {
        MemorySummarizer stub = new StubSummarizer(List.of());
        LongTermMaintainer m = new LongTermMaintainer(
                new org.example.agent.context.session.SessionMessageStore(tempDir.resolve(".agent/sessions")),
                stub,
                new PendingLongTermCandidates(),
                new MemoryIndexSynchronizer(memoryIndex, longTermStore, midTermStore, null, taskPlanRepository),
                1, 2);
        m.setActiveSessionId("s1");
        int first = m.tick();
        assertThat(first).isEqualTo(0);
    }

    @Test
    void maintainerDoesNotReprocessSameTurns() {
        List<MemorySummarizer.ExtractedCandidate> cands = List.of(
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.FEEDBACK,
                        "短期滑窗被替换",
                        "短期滑窗被替换", 4, false, "NEW", "context-assembly",
                        "user: 改", "对齐 part4"));

        MemorySummarizer stub = new StubSummarizer(cands);
        org.example.agent.context.session.SessionMessageStore sessionStore =
                new org.example.agent.context.session.SessionMessageStore(tempDir.resolve(".agent/sessions"));

        String sid = "s2";
        sessionStore.addUser(sid, "turn 1 user");
        sessionStore.addAssistant(sid, "turn 1 assistant");
        sessionStore.addUser(sid, "turn 2 user");
        sessionStore.addAssistant(sid, "turn 2 assistant");
        sessionStore.addUser(sid, "turn 3 user");
        sessionStore.addAssistant(sid, "turn 3 assistant");

        LongTermMaintainer m = new LongTermMaintainer(
                sessionStore, stub, new PendingLongTermCandidates(),
                new MemoryIndexSynchronizer(memoryIndex, longTermStore, midTermStore, null, taskPlanRepository),
                1, 2);
        m.setActiveSessionId(sid);

        int first = m.tick();
        int second = m.tick();
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    // ============ MemoryRecallScorer ============

    @Test
    void recallScorerReturnsEmptyWhenNoMatch() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore, midTermStore,
                0.35, 5, 14, 0.45, 0.20, 0.20, 0.15);
        longTermStore.createTopic("spring-config", "Spring 配置", List.of("Spring"), List.of("spring"), 3, false);
        // 与主题不相关的 query
        List<MemoryRecallScorer.Scored> result = scorer.score("完全不相关的查询", "s1");
        assertThat(result).isEmpty();
    }

    @Test
    void recallScorerReturnsMatchedTopics() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore, midTermStore,
                0.10, 5, 14, 0.45, 0.20, 0.20, 0.15);
        longTermStore.createTopic("spring-config", "Spring 配置约定", List.of("Spring"), List.of("spring", "dashscope"), 3, false);

        List<MemoryRecallScorer.Scored> result = scorer.score("spring 配置", "s1");
        assertThat(result).hasSizeGreaterThanOrEqualTo(1);
        assertThat(result.get(0).getPath()).contains("spring-config");
    }

    @Test
    void recallScorerPinnedEntriesAlwaysIncluded() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore, midTermStore,
                0.35, 5, 14, 0.45, 0.20, 0.20, 0.15);
        LongTermStore.Topic topic = longTermStore.createTopic("red-line",
                "项目红线", List.of("红线"), List.of("red", "line"), 5, true);
        longTermStore.appendToTopic(topic, new LongTermStore.Entry(
                "", LongTermStore.Category.FEEDBACK, "禁止直接改 entity", 5, true,
                "user: 不能改", "违反会有 schema 风险", "red-line",
                java.time.Instant.now(), false));

        List<MemoryRecallScorer.Scored> pinned = scorer.loadPinnedEntries();
        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).isPinned()).isTrue();
    }

    @Test
    void recallScorerEmptyQueryReturnsOnlyPinned() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore, midTermStore,
                0.35, 5, 14, 0.45, 0.20, 0.20, 0.15);
        longTermStore.createTopic("spring-config", "Spring 配置", List.of(), List.of("spring"), 3, false);
        longTermStore.createTopic("red-line", "红线", List.of(), List.of(), 5, true);

        // query 为空 → 只返回 pinned（importance=5）
        List<MemoryRecallScorer.Scored> result = scorer.score(null, "s1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isPinned()).isTrue();
    }

    // ============ test stubs ============

    static final class StubSummarizer implements MemorySummarizer {
        private final List<ExtractedCandidate> candidates;
        StubSummarizer(List<ExtractedCandidate> c) { this.candidates = c; }

        @Override
        public MidTermPatch summarizeMidTermTurn(String sessionId, List<org.springframework.ai.chat.messages.Message> turn, MidTermStore.MidTerm previous) {
            return MidTermPatch.empty();
        }

        @Override
        public MidTermStore.MidTerm regenerateMidTerm(String sessionId, List<org.springframework.ai.chat.messages.Message> all) {
            return MidTermStore.MidTerm.empty(sessionId);
        }

        @Override
        public List<ExtractedCandidate> extractLongTermCandidates(List<org.springframework.ai.chat.messages.Message> unsummarized,
                                                                  List<String> existingTopics,
                                                                  List<String> existingTitles) {
            return candidates == null ? List.of() : candidates;
        }
    }
}
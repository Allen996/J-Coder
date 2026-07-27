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
 * 记忆系统 package 的核心组件测试（part4.md §7.2 / §7.4 / §7.5）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>{@link MidTermStore} —— 5 字段 round-trip + 解析</li>
 *   <li>{@link LongTermStore} —— 多类别追加 / 持久化 / 解析</li>
 *   <li>{@link MemoryIndex} —— LRU 20 淘汰 + 匹配 topN</li>
 *   <li>{@link MemoryIndexSynchronizer} —— 启动同步 + 重复注册幂等</li>
 *   <li>{@link PendingLongTermCandidates} —— confirm / reject 不影响其他候选</li>
 *   <li>{@link FlashMemorySummarizer} —— 启发式 mid-term + 候选 YAML 解析</li>
 *   <li>{@link LongTermMaintainer} —— 不会重复消费同样的 turns</li>
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
        // 用 tempDir 作 projectRoot;各 store 通过 Path 显式注入
        longTermStore = new LongTermStore(tempDir.resolve("Nico.md"));
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
    void midTermRoundTripPreservesAll5Fields() {
        String sessionId = "s-2026-07-24-001";
        MidTermStore.MidTerm mt = new MidTermStore.MidTerm(
                sessionId,
                "实现 part4 记忆系统",
                "- 实现 ShortTermStore\n- 实现 MidTermStore",
                "短期加载语义改为最近5轮递减",
                "字典抽象对模型不可见",
                "完成 /memory CLI",
                java.time.Instant.parse("2026-07-24T10:00:00Z"));

        midTermStore.updateMidTerm(sessionId, mt);

        MidTermStore.MidTerm loaded = midTermStore.loadOrEmpty(sessionId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getSessionId()).isEqualTo(sessionId);
        assertThat(loaded.getSessionGoal()).contains("实现 part4");
        assertThat(loaded.getCompleted()).contains("ShortTermStore").contains("MidTermStore");
        assertThat(loaded.getDecisions()).contains("最近5轮");
        assertThat(loaded.getLessons()).contains("字典抽象");
        assertThat(loaded.getPendingTodos()).contains("/memory CLI");
    }

    @Test
    void midTermHandlesMissingFileGracefully() {
        MidTermStore.MidTerm mt = midTermStore.loadOrEmpty("nonexistent");
        assertThat(mt).isNull();
    }

    // ============ LongTermStore ============

    @Test
    void longTermAppendPersistsAcrossReload() {
        longTermStore.append(new LongTermStore.Entry(
                LongTermStore.Category.RED_LINE, "不要修改 entity", 5, java.time.Instant.now()));
        longTermStore.append(new LongTermStore.Entry(
                LongTermStore.Category.CODING_STYLE, "公共 API 必须有单测", 4, java.time.Instant.now()));
        longTermStore.append(new LongTermStore.Entry(
                LongTermStore.Category.DECISION, "短期加载用最近5轮递减", 5, java.time.Instant.now()));

        LongTermStore reloaded = new LongTermStore(tempDir.resolve("Nico.md"));
        List<LongTermStore.Entry> entries = reloaded.loadOrEmpty();
        assertThat(entries).hasSize(3);
        assertThat(entries.stream().map(LongTermStore.Entry::getCategory))
                .contains(LongTermStore.Category.RED_LINE,
                        LongTermStore.Category.CODING_STYLE,
                        LongTermStore.Category.DECISION);
    }

    // ============ MemoryIndex ============

    @Test
    void memoryIndexLruEvictsAtCapacity() {
        MemoryIndex idx = new MemoryIndex(tempDir.resolve("MEMORY.md"), 3);
        idx.add("a.md", "A");
        idx.add("b.md", "B");
        idx.add("c.md", "C");
        idx.add("d.md", "D"); // over capacity

        List<MemoryIndex.IndexEntry> entries = idx.loadOrEmpty();
        assertThat(entries).hasSize(3);
        // LRU = 头插;最新的 d.md 在索引[0]
        assertThat(entries.get(0).getPath()).isEqualTo("d.md");
        // a.md 应该被淘汰
        assertThat(entries).noneMatch(e -> "a.md".equals(e.getPath()));
    }

    @Test
    void memoryIndexMatchTopNReturnsRelevantEntries() {
        MemoryIndex idx = new MemoryIndex(tempDir.resolve("MEMORY.md"), 10);
        idx.add("Nico.md", "项目骨架与红线");
        idx.add(".agent/sessions/abc/short-term.md", "会话 abc 短期对话流");
        idx.add(".agent/sessions/abc/mid-term.md", "会话 abc 中期摘要");
        idx.add(".agent/sessions/xyz/short-term.md", "会话 xyz 短期对话流");

        List<MemoryIndex.IndexEntry> hits = idx.matchTopN("abc", 5);
        assertThat(hits).hasSizeGreaterThanOrEqualTo(2);
        assertThat(hits).allMatch(e -> e.getPath().contains("abc") || e.getSummary().contains("abc"));
    }

    @Test
    void memoryIndexIsIdempotentOnReAdd() {
        MemoryIndex idx = new MemoryIndex(tempDir.resolve("MEMORY.md"), 20);
        idx.add("Nico.md", "项目骨架");
        idx.add("Nico.md", "项目骨架");
        idx.add("Nico.md", "项目骨架");

        assertThat(idx.loadOrEmpty()).hasSize(1);
        assertThat(idx.loadOrEmpty().get(0).getPath()).isEqualTo("Nico.md");
    }

    // ============ PendingLongTermCandidates ============

    @Test
    void pendingConfirmRemovesAndRejectKeepsOtherIntact() {
        PendingLongTermCandidates pending = new PendingLongTermCandidates();
        List<MemorySummarizer.ExtractedCandidate> cands = List.of(
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.RED_LINE,
                        "禁止直接改 entity", 5, "user: 不能改", "违反会有 schema 风险"),
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.CODING_STYLE,
                        "公共 API 必有单测", 4, "user: 加测试", "团队基本约定"),
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.DECISION,
                        "短期用最近5轮", 5, "user: 改语义", "避免滑窗淘汰"));

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
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.CONVENTION, "", 5, "", ""));
        assertThat(pending.add(cands, "s1")).isEmpty();
        assertThat(pending.size()).isEqualTo(0);
    }

    // ============ MemoryIndexSynchronizer ============

    @Test
    void memoryIndexSynchronizerIsIdempotent() throws Exception {
        Path sessionsRoot = tempDir.resolve(".agent/sessions");
        Files.createDirectories(sessionsRoot.resolve("s-1"));
        Files.writeString(sessionsRoot.resolve("s-1/short-term.md"), "fake");
        Files.writeString(sessionsRoot.resolve("s-1/mid-term.md"), "fake");

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, midTermStore, null, taskPlanRepository);
        sync.syncKnownPaths();
        int sizeAfterFirst = memoryIndex.loadOrEmpty().size();

        // 再调一次不能增加条目
        sync.syncKnownPaths();
        assertThat(memoryIndex.loadOrEmpty().size()).isEqualTo(sizeAfterFirst);
        assertThat(memoryIndex.loadOrEmpty().stream().anyMatch(e -> "Nico.md".equals(e.getPath()))).isTrue();
        assertThat(memoryIndex.loadOrEmpty().stream()
                .anyMatch(e -> e.getPath().equals(".agent/sessions/s-1/mid-term.md"))).isTrue();
        assertThat(memoryIndex.loadOrEmpty().stream()
                .anyMatch(e -> e.getPath().equals(".agent/sessions/s-1/short-term.md"))).isTrue();
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
    void flashSummarizerFallsBackToHeuristicWhenNoModel() {
        FlashMemorySummarizer summarizer = new FlashMemorySummarizer(null); // 无 ChatModel
        List<org.springframework.ai.chat.messages.Message> empty = List.of();
        MemorySummarizer.MidTermPatch patch = summarizer.summarizeMidTermTurn("s1", empty, null);
        assertThat(patch.isEmpty()).isTrue();

        List<org.springframework.ai.chat.messages.Message> msgs = List.of(
                new org.springframework.ai.chat.messages.UserMessage("用户问 X 是怎么实现的？"));
        MemorySummarizer.MidTermPatch patch2 = summarizer.summarizeMidTermTurn("s1", msgs, null);
        assertThat(patch2.getDeltaCompleted()).contains("用户问");
    }

    @Test
    void flashSummarizerParsesYmlCandidateList() {
        String yaml = """
                - category: red_line
                  content: 不要修改生成的 entity 类
                  importance: 5
                  evidence: user: 不能改 entity
                  reason: 违反会导致 schema 不一致
                - category: coding_style
                  content: 所有公共 API 都要有单测
                  importance: 4
                  evidence: user: 加测试
                  reason: 团队约定
                """;

        List<MemorySummarizer.ExtractedCandidate> parsed = FlashMemorySummarizer.parseCandidatesYaml(yaml);
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).getCategory()).isEqualTo(LongTermStore.Category.RED_LINE);
        assertThat(parsed.get(0).getImportance()).isEqualTo(5);
        assertThat(parsed.get(1).getCategory()).isEqualTo(LongTermStore.Category.CODING_STYLE);
    }

    @Test
    void flashSummarizerReadsMidTermFieldsAndIgnoresUnknown() {
        String raw = """
                session_goal: 设计三层记忆
                delta_completed: 写完 §7.2
                delta_decisions: 短期不滑窗
                delta_lessons: 字典对模型不可见
                delta_pending_todos: 实现 LongTermMaintainer
                some_other_key: 应该被忽略
                """;
        MemorySummarizer.MidTermPatch patch = FlashMemorySummarizer.parseMidTermPatch(raw);
        assertThat(patch).isNotNull();
        assertThat(patch.getNewSessionGoal()).contains("设计三层记忆");
        assertThat(patch.getDeltaCompleted()).contains("§7.2");
        assertThat(patch.getDeltaDecisions()).contains("不滑窗");
        assertThat(patch.getDeltaLessons()).contains("字典");
        assertThat(patch.getDeltaPendingTodos()).contains("LongTermMaintainer");
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
                new MemorySummarizer.ExtractedCandidate(LongTermStore.Category.CONVENTION,
                        "短期滑窗被替换", 4, "user: 改", "对齐 part4"));

        MemorySummarizer stub = new StubSummarizer(cands);
        org.example.agent.context.session.SessionMessageStore sessionStore =
                new org.example.agent.context.session.SessionMessageStore(tempDir.resolve(".agent/sessions"));

        // 添加 3 条 user + 3 assistant
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
        assertThat(second).isEqualTo(0); // 已消费过的 turns 不重复提取
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
            return new MidTermStore.MidTerm(sessionId, "(stub)", "", "", "", "", java.time.Instant.now());
        }

        @Override
        public List<ExtractedCandidate> extractLongTermCandidates(List<org.springframework.ai.chat.messages.Message> unsummarized) {
            return candidates == null ? List.of() : candidates;
        }
    }
}

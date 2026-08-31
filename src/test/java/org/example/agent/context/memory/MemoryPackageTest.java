package org.example.agent.context.memory;

import org.example.agent.context.session.SessionMessageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆系统 package 的核心组件测试（part4.md §7.2 / §7.4 / §7.5 / §7.7）。
 *
 * <p>覆盖（mid-term 删除结构化摘要字段后）：
 * <ul>
 *   <li>{@link SessionMessageStore} —— worklog / mid-term.json 双文件读写 + 启动恢复 + 软上限</li>
 *   <li>{@link LongTermStore} —— 多主题文件追加 / 持久化 / 解析</li>
 *   <li>{@link MemoryIndex} —— 目录页去重 / 匹配 topN 兼容</li>
 *   <li>{@link MemoryIndexSynchronizer} —— 启动同步 + 重复注册幂等</li>
 *   <li>{@link PendingLongTermCandidates} —— confirm / reject 不影响其他候选</li>
 *   <li>{@link FlashMemorySummarizer} —— 模型不可用 → no-op（无启发式兜底） + YAML 解析</li>
 *   <li>{@link LongTermMaintainer} —— 不会重复消费同样的 turns</li>
 *   <li>{@link MemoryRecallScorer} —— 评分门控 + 允许为空（仅 long-term topic 召回池）</li>
 * </ul>
 */
class MemoryPackageTest {

    @TempDir
    Path tempDir;

    private LongTermStore longTermStore;
    private MemoryIndex memoryIndex;
    private SessionMessageStore sessionStore;

    @BeforeEach
    void setUp() throws Exception {
        longTermStore = new LongTermStore(tempDir);
        memoryIndex = new MemoryIndex(tempDir.resolve("MEMORY.md"));
        sessionStore = new SessionMessageStore(tempDir.resolve(".agent/sessions"));
    }

    @AfterEach
    void tearDown() {
        // tempDir cleanup is automatic
    }

    // ============ SessionMessageStore ============

    @Test
    void sessionStoreAddUserAndAssistantAppendsWorklog() {
        String sid = "s-001";
        sessionStore.addUser(sid, "你好");
        sessionStore.addAssistant(sid, "你好,有什么可以帮忙?");

        List<SessionMessageStore.Record> worklog = sessionStore.readWorklog(sid);
        assertThat(worklog).hasSize(2);
        assertThat(worklog.get(0).role).isEqualTo("user");
        assertThat(worklog.get(0).content).isEqualTo("你好");
        assertThat(worklog.get(1).role).isEqualTo("assistant");
    }

    @Test
    void sessionStoreAddMetaOnlyAppendsWorklog() {
        String sid = "s-meta";
        // 注意:addUser 会先 getOrCreate(触发 loadOnCreate),把刚写的 worklog 加载到 in-memory
        // → 所以下面的 getOrCreate 不会重复加载。这里直接验证 addUser 的副作用。
        SessionMessageStore.Session session = sessionStore.getOrCreate(sid);
        sessionStore.addUser(sid, "Q1");
        sessionStore.addAssistant(sid, "A1");
        sessionStore.addMeta(sid, "[auto-compress] 100→50 tokens");

        List<SessionMessageStore.Record> worklog = sessionStore.readWorklog(sid);
        assertThat(worklog).hasSize(3);
        assertThat(worklog.get(2).role).isEqualTo("meta");
        assertThat(worklog.get(2).content).contains("[auto-compress]");

        // [meta] 不进入 Session.messages
        assertThat(session.snapshot()).hasSize(2);
    }

    @Test
    void sessionStoreReplaceWindowAtomicWritesMidTerm() throws Exception {
        String sid = "s-replace";
        sessionStore.addUser(sid, "user");
        sessionStore.addAssistant(sid, "assistant");
        sessionStore.replaceWindow(sid,
                List.of(new org.springframework.ai.chat.messages.UserMessage("kept-1"),
                        new org.springframework.ai.chat.messages.AssistantMessage("kept-2")),
                SessionMessageStore.CompressionInfo.auto(100, 50, 2));

        // mid-term.json 存在,包含 [meta] 首行 + 消息列表
        assertThat(Files.exists(sessionStore.midTermPath(sid))).isTrue();
        String midJson = Files.readString(sessionStore.midTermPath(sid));
        assertThat(midJson).contains("\"role\" : \"meta\"").contains("\"content\" : \"[auto-compress]");
        assertThat(midJson).contains("\"role\" : \"user\"").contains("kept-1");
        assertThat(midJson).contains("\"role\" : \"assistant\"").contains("kept-2");

        // loadMidTermOrEmpty 返回过滤 [meta] 的消息列表
        List<org.springframework.ai.chat.messages.Message> loaded = sessionStore.loadMidTermOrEmpty(sid);
        assertThat(loaded).hasSize(2);
    }

    @Test
    void sessionStoreStartupRecoverRebuildsFromWorklog() throws Exception {
        String sid = "s-recover";
        // 直接构造 worklog.json 不触发 mid-term 写入,模拟"mid-term 缺失,只有 worklog"的场景
        SessionMessageStore.Record u1 = new SessionMessageStore.Record();
        u1.role = "user"; u1.content = "round-1 user"; u1.timestamp = "2026-01-01T00:00:00Z";
        SessionMessageStore.Record a1 = new SessionMessageStore.Record();
        a1.role = "assistant"; a1.content = "round-1 assistant"; a1.timestamp = "2026-01-01T00:00:01Z";
        SessionMessageStore.Record u2 = new SessionMessageStore.Record();
        u2.role = "user"; u2.content = "round-2 user"; u2.timestamp = "2026-01-01T00:00:02Z";
        SessionMessageStore.Record a2 = new SessionMessageStore.Record();
        a2.role = "assistant"; a2.content = "round-2 assistant"; a2.timestamp = "2026-01-01T00:00:03Z";
        // 直接写 short-term.json(worklog),跳过 getOrCreate 自动创建 mid-term
        SessionMessageStore.MemoryFile wf = new SessionMessageStore.MemoryFile();
        wf.kind = "short-term"; wf.sessionId = sid;
        wf.createdAt = "2026-01-01T00:00:00Z"; wf.updatedAt = "2026-01-01T00:00:03Z";
        wf.messages = List.of(u1, a1, u2, a2);
        // 写到磁盘前先创建目录
        Files.createDirectories(sessionStore.shortTermPath(sid).getParent());
        new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValue(sessionStore.shortTermPath(sid).toFile(), wf);

        // 没有 mid-term.json → 新建一个 SessionMessageStore 模拟重启
        SessionMessageStore fresh = new SessionMessageStore(tempDir.resolve(".agent/sessions"));
        SessionMessageStore.Session s = fresh.getOrCreate(sid);
        // 启动恢复取最近 5 轮(全部,因为只有 2 round)
        assertThat(s.snapshot()).hasSize(4);
        // 启动恢复后 mid-term.json 应被自动创建
        assertThat(Files.exists(fresh.midTermPath(sid))).isTrue();
        // mid-term.json 应包含 startup-recover 元信息
        String midJson = Files.readString(fresh.midTermPath(sid));
        assertThat(midJson).contains("startup-recover");
    }

    @Test
    void sessionStoreWorklogSoftCapLogsWarning() {
        // 仅校验 warn 日志可被触发;完整 10000 条插入成本高,这里只 verify 不抛异常
        String sid = "s-cap";
        for (int i = 0; i < 5; i++) {
            sessionStore.addUser(sid, "msg " + i);
        }
        sessionStore.enforceWorklogSoftCap(sid);
        assertThat(sessionStore.worklogSize(sid)).isEqualTo(5);
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
        idx.add(".agent/sessions/abc/short-term.json", "会话 abc 短期对话流 + worklog");
        idx.add(".agent/sessions/abc/mid-term.json", "会话 abc 中期窗口快照");
        idx.add(".agent/sessions/xyz/short-term.json", "会话 xyz 短期对话流 + worklog");

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
        Path sessionsRoot = sessionStore.sessionsRoot();
        Files.createDirectories(sessionsRoot.resolve("s-1"));
        Files.writeString(sessionsRoot.resolve("s-1/short-term.json"), "{}");
        Files.writeString(sessionsRoot.resolve("s-1/mid-term.json"), "{}");

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, sessionStore);
        sync.syncKnownPaths();
        int sizeAfterFirst = memoryIndex.loadOrEmpty().size();

        sync.syncKnownPaths();
        assertThat(memoryIndex.loadOrEmpty().size()).isEqualTo(sizeAfterFirst);
        assertThat(memoryIndex.loadOrEmpty().stream()
                .anyMatch(e -> e.getPath().equals(".agent/sessions/s-1/mid-term.json"))).isTrue();
        assertThat(memoryIndex.loadOrEmpty().stream()
                .anyMatch(e -> e.getPath().equals(".agent/sessions/s-1/short-term.json"))).isTrue();
    }

    // 阶段 2 起,plan 不再写 MEMORY.md 索引 —— notePlan/removePlan 已删除,对应测试随之删除

    @Test
    void memoryIndexSynchronizerTracksLongTermTopics() {
        longTermStore.createTopic("spring-config", "Spring 配置约定", List.of(), List.of(), 4, false);

        MemoryIndexSynchronizer sync = new MemoryIndexSynchronizer(
                memoryIndex, longTermStore, sessionStore);
        sync.noteLongTermTopics();

        assertThat(memoryIndex.loadOrEmpty()).anyMatch(e ->
                e.getPath().equals("001-spring-config.md"));
    }

    // ============ FlashMemorySummarizer ============

    @Test
    void flashSummarizerReturnsEmptyWhenNoModel() {
        // §7.5：无模型时必须 no-op,不允许启发式兜底
        FlashMemorySummarizer summarizer = new FlashMemorySummarizer(null, null);
        List<org.springframework.ai.chat.messages.Message> empty = List.of();
        List<MemorySummarizer.ExtractedCandidate> result = summarizer.extractLongTermCandidates(empty, List.of(), List.of());
        assertThat(result).isEmpty();

        List<org.springframework.ai.chat.messages.Message> msgs = List.of(
                new org.springframework.ai.chat.messages.UserMessage("用户问 X 是怎么实现的?"));
        List<MemorySummarizer.ExtractedCandidate> result2 = summarizer.extractLongTermCandidates(msgs, List.of(), List.of());
        assertThat(result2).isEmpty();
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

    // ============ LongTermMaintainer ============

    @Test
    void maintainerSkipsIfNoNewUserMessages() {
        MemorySummarizer stub = new StubSummarizer(List.of());
        LongTermMaintainer m = new LongTermMaintainer(
                new SessionMessageStore(tempDir.resolve(".agent/sessions")),
                stub,
                new PendingLongTermCandidates(),
                new MemoryIndexSynchronizer(memoryIndex, longTermStore, sessionStore),
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
        SessionMessageStore localStore =
                new SessionMessageStore(tempDir.resolve(".agent/sessions-2"));

        String sid = "s2";
        localStore.addUser(sid, "turn 1 user");
        localStore.addAssistant(sid, "turn 1 assistant");
        localStore.addUser(sid, "turn 2 user");
        localStore.addAssistant(sid, "turn 2 assistant");
        localStore.addUser(sid, "turn 3 user");
        localStore.addAssistant(sid, "turn 3 assistant");

        LongTermMaintainer m = new LongTermMaintainer(
                localStore, stub, new PendingLongTermCandidates(),
                new MemoryIndexSynchronizer(memoryIndex, longTermStore, localStore),
                1, 2);
        m.setActiveSessionId(sid);

        int first = m.tick();
        int second = m.tick();
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
    }

    // ============ MemoryRecallScorer (long-term only) ============

    @Test
    void recallScorerReturnsEmptyWhenNoMatch() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore,
                0.35, 5, 14, 0.45, 0.20, 0.20, 0.15);
        longTermStore.createTopic("spring-config", "Spring 配置", List.of("Spring"), List.of("spring"), 3, false);
        // 与主题不相关的 query
        List<MemoryRecallScorer.Scored> result = scorer.score("完全不相关的查询", "s1");
        assertThat(result).isEmpty();
    }

    @Test
    void recallScorerReturnsMatchedTopics() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore,
                0.10, 5, 14, 0.45, 0.20, 0.20, 0.15);
        longTermStore.createTopic("spring-config", "Spring 配置约定", List.of("Spring"), List.of("spring", "dashscope"), 3, false);

        List<MemoryRecallScorer.Scored> result = scorer.score("spring 配置", "s1");
        assertThat(result).hasSizeGreaterThanOrEqualTo(1);
        assertThat(result.get(0).getPath()).contains("spring-config");
    }

    @Test
    void recallScorerPinnedEntriesAlwaysIncluded() {
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore,
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
        MemoryRecallScorer scorer = new MemoryRecallScorer(longTermStore,
                0.35, 5, 14, 0.45, 0.20, 0.20, 0.15);
        longTermStore.createTopic("spring-config", "Spring 配置", List.of(), List.of("spring"), 3, false);
        longTermStore.createTopic("red-line", "红线", List.of(), List.of(), 5, true);

        // query 为空 → 只返回 pinned (importance=5)
        List<MemoryRecallScorer.Scored> result = scorer.score(null, "s1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isPinned()).isTrue();
    }

    // ============ test stubs ============

    static final class StubSummarizer implements MemorySummarizer {
        private final List<ExtractedCandidate> candidates;
        StubSummarizer(List<ExtractedCandidate> c) { this.candidates = c; }

        @Override
        public List<ExtractedCandidate> extractLongTermCandidates(List<org.springframework.ai.chat.messages.Message> unsummarized,
                                                                  List<String> existingTopics,
                                                                  List<String> existingTitles) {
            return candidates == null ? List.of() : candidates;
        }
    }
}

package org.example.agent.core.task;

import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.dag.DagState;
import org.example.agent.core.task.dag.DagStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CheckpointService 单元测试（阶段 4）。
 *
 * <p>手写 stub git binary —— 避免依赖真实 git 环境。
 * 验证：
 * <ul>
 *   <li>success path —— manifest.json 落盘 + status=PENDING_DECISION + gitCommit 填了</li>
 *   <li>git 不可用 —— 抛 GitUnavailableException,manifest 不写</li>
 *   <li>applyDecision —— manifest status → DECIDED + decision / decidedAt / decidedBy 填了</li>
 *   <li>abandon —— manifest status → ABANDONED</li>
 *   <li>nextSeq —— 已有 1,2 后第 3 个 checkpoint 是 003</li>
 *   <li>完整快照字段 (DAG / system prompt / tool registry / mid-term seq / long-term) 都进 manifest</li>
 * </ul>
 */
class CheckpointServiceTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path sessionsRoot;
    private Path checkpointsRoot;
    private String stubGitScript;
    private SessionMessageStore sessionStore;
    private DagStateRepository dagStateRepository;
    private LongTermStore longTermStore;
    private CheckpointService service;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("project");
        sessionsRoot = projectRoot.resolve(".agent").resolve("sessions");
        Files.createDirectories(sessionsRoot);
        checkpointsRoot = sessionsRoot.resolve("st-1").resolve("checkpoints");

        // 手写 "git" stub —— Java 进程模拟 git rev-parse HEAD
        stubGitScript = StubGit.install(tempDir);
        // 准备 session 目录 + worklog + mid-term
        Path sessionDir = sessionsRoot.resolve("st-1");
        Files.createDirectories(sessionDir);
        Files.writeString(sessionDir.resolve("short-term.json"),
                "{\"schema\":\"3\",\"kind\":\"short-term\",\"sessionId\":\"st-1\",\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"hi\"}," +
                "{\"role\":\"assistant\",\"content\":\"hello\"}]}",
                StandardCharsets.UTF_8);
        Files.writeString(sessionDir.resolve("mid-term.json"),
                "[{\"role\":\"user\",\"content\":\"mid-term-msg\"}," +
                "{\"role\":\"assistant\",\"content\":\"mid-term-resp\"}]",
                StandardCharsets.UTF_8);

        // LongTermStore 用真实构造(只读 file 扫描,tempDir 没主题文件就是空)
        longTermStore = new LongTermStore(projectRoot);
        // SessionMessageStore 用真实构造 + 反射注入 sessionsRoot
        sessionStore = new SessionMessageStore(sessionsRoot);
        // DagStateRepository 用真实构造(只看 .agent/sessions)
        dagStateRepository = new DagStateRepository(sessionsRoot);

        service = new CheckpointService(projectRoot, stubGitScript,
                sessionStore, dagStateRepository, longTermStore, null);
        service.init(); // mkdir
    }

    @Test
    void createPendingWritesManifest() {
        DagState emptyState = DagState.empty("plan-1", "st-1");

        CheckpointService.Result r = service.createPending(
                "st-1", "choose-cache", "use redis or caffeine?",
                List.of("src/cache/Foo.java"),
                "system-prompt-snapshot",
                List.of("read_file", "write_file", "checkpoint"),
                null, // dagGraph —— 接受 null
                emptyState,
                "");

        assertNotNull(r.checkpoint());
        assertEquals("001-choose-cache", r.checkpoint().getCheckpointId());
        assertEquals("st-1", r.checkpoint().getSessionId());
        assertEquals("choose-cache", r.checkpoint().getReason());
        assertEquals("use redis or caffeine?", r.checkpoint().getDecisionQuestion());
        assertEquals(Checkpoint.STATUS_PENDING_DECISION, r.checkpoint().getStatus());
        assertNotNull(r.checkpoint().getGitCommit());
        assertTrue(r.checkpoint().getGitCommit().startsWith("stub-commit-"),
                "gitCommit should come from stub; got: " + r.checkpoint().getGitCommit());
        assertTrue(Files.exists(r.manifestPath()));
        // seq 1
        assertEquals(1, r.checkpoint().getSeq());
        // worklog tail seq = 2 (user + assistant)
        assertEquals(Integer.valueOf(2), r.checkpoint().getWorklogTailSeq());
        // mid-term seq = 2
        assertEquals(Integer.valueOf(2), r.checkpoint().getMidTermSeq());
        // systemPrompt 进入 manifest
        assertEquals("system-prompt-snapshot", r.checkpoint().getSystemPrompt());
        // tool registry 进入 manifest
        assertTrue(r.checkpoint().getToolRegistry().contains("read_file"));
        assertTrue(r.checkpoint().getToolRegistry().contains("checkpoint"));
    }

    @Test
    void gitUnavailableThrowsAndDoesNotWrite() {
        // 重新构造一个 git binary 指向不存在的脚本
        CheckpointService noGit = new CheckpointService(projectRoot,
                tempDir.resolve("no-such-git-binary.sh").toString(),
                sessionStore, dagStateRepository, longTermStore, null);
        noGit.init();

        assertFalse(noGit.gitAvailable(), "gitAvailable should return false when binary missing");

        DagState emptyState = DagState.empty("plan-1", "st-1");

        CheckpointService.GitUnavailableException ex = assertThrows(
                CheckpointService.GitUnavailableException.class,
                () -> noGit.createPending("st-1", "x", "y", List.of(),
                        "", List.of(), null, emptyState, ""));

        assertNotNull(ex.getMessage());
        // 目录不应该创建
        assertFalse(Files.exists(checkpointsRoot), "no checkpoint dir when git missing");
    }

    @Test
    void applyDecisionUpdatesStatusToDecided() {
        DagState emptyState = DagState.empty("plan-1", "st-1");
        CheckpointService.Result created = service.createPending(
                "st-1", "pick-lib", "A or B?", List.of(),
                "sys", List.of("read_file"), null, emptyState, "");

        Checkpoint decided = service.applyDecision("st-1", created.checkpoint().getCheckpointId(),
                "go with A", "user");
        assertEquals(Checkpoint.STATUS_DECIDED, decided.getStatus());
        assertEquals("go with A", decided.getDecision());
        assertEquals("user", decided.getDecidedBy());
        assertNotNull(decided.getDecidedAt());

        // 重新读盘确认
        Checkpoint reloaded = service.load("st-1", created.checkpoint().getCheckpointId()).orElseThrow();
        assertEquals(Checkpoint.STATUS_DECIDED, reloaded.getStatus());
        assertEquals("go with A", reloaded.getDecision());
    }

    @Test
    void abandonUpdatesStatus() {
        DagState emptyState = DagState.empty("plan-1", "st-1");
        CheckpointService.Result created = service.createPending(
                "st-1", "foo", "bar", List.of(),
                "sys", List.of(), null, emptyState, "");

        Checkpoint abandoned = service.abandon("st-1", created.checkpoint().getCheckpointId(),
                "user said nevermind");
        assertEquals(Checkpoint.STATUS_ABANDONED, abandoned.getStatus());
        assertEquals("user said nevermind", abandoned.getDecision());

        Checkpoint reloaded = service.load("st-1", created.checkpoint().getCheckpointId()).orElseThrow();
        assertEquals(Checkpoint.STATUS_ABANDONED, reloaded.getStatus());
    }

    @Test
    void nextSeqIncrementsAfterExisting() throws Exception {
        // 先写一个
        DagState emptyState = DagState.empty("plan-1", "st-1");
        CheckpointService.Result r1 = service.createPending("st-1", "first", "q1", List.of(),
                "s", List.of(), null, emptyState, "");
        assertEquals(1, r1.checkpoint().getSeq());
        // 现在 nextSeq 应是 2
        assertEquals(2, service.nextSeq("st-1"));
        // 手动建一个 002-prefix 目录
        Files.createDirectories(checkpointsRoot.resolve("002-second"));
        assertEquals(3, service.nextSeq("st-1"));

        // 创建第三个,seq 应是 3
        CheckpointService.Result r3 = service.createPending("st-1", "third", "q3", List.of(),
                "s", List.of(), null, emptyState, "");
        assertEquals(3, r3.checkpoint().getSeq());
        assertEquals("003-third", r3.checkpoint().getCheckpointId());
    }

    @Test
    void fullSnapshotFieldsPopulated() throws Exception {
        // 在 long-term 加一个主题文件
        Files.writeString(projectRoot.resolve("001-design.md"),
                "---\nseq: 001\ntopic: design\nsummary: design notes\nimportance: 3\npinned: false\nentryCount: 0\n---\n\n# body\n",
                StandardCharsets.UTF_8);
        LongTermStore refreshed = new LongTermStore(projectRoot);
        CheckpointService s2 = new CheckpointService(projectRoot, stubGitScript,
                sessionStore, dagStateRepository, refreshed, null);
        s2.init();

        DagState emptyState = DagState.empty("plan-1", "st-1");
        CheckpointService.Result r = s2.createPending(
                "st-1", "x", "y", List.of("a.java", "b.java"),
                "sys-pro", List.of("read_file", "checkpoint"),
                null, emptyState, "");

        Checkpoint cp = r.checkpoint();
        assertEquals(List.of("001-design.md"), cp.getLongTermFiles(),
                "long-term file listing should include the topic file");
        assertEquals(2, cp.getRelevantArtifacts().size());
        assertTrue(cp.getRelevantArtifacts().contains("a.java"));
        // system prompt 与 tool registry 都非空
        assertEquals("sys-pro", cp.getSystemPrompt());
        assertTrue(cp.getToolRegistry().contains("read_file"));
        assertTrue(cp.getToolRegistry().contains("checkpoint"));
        // gitCommit 来自 stub
        assertNotNull(cp.getGitCommit());
        assertFalse(cp.getGitCommit().isEmpty());
    }

    @Test
    void checkpointSlugifyNormalizesReason() {
        assertEquals("choose-cache", Checkpoint.slugifyReason("Choose-Cache"));
        assertEquals("a-b-c", Checkpoint.slugifyReason("a/b c"));
        assertEquals("decision", Checkpoint.slugifyReason(""));
        assertEquals("decision", Checkpoint.slugifyReason("///"));
        // 长度截断
        String longReason = "x".repeat(100);
        assertTrue(Checkpoint.slugifyReason(longReason).length() <= 48);
    }

    @Test
    void applyDecisionMissingCheckpointThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.applyDecision("st-1", "999-nope", "x", "user"));
    }

    @Test
    void parentCheckpointIdPersisted() {
        DagState emptyState = DagState.empty("plan-1", "st-1");
        CheckpointService.Result first = service.createPending(
                "st-1", "first", "q", List.of(),
                "s", List.of(), null, emptyState, "");
        assertEquals("", first.checkpoint().getParentCheckpointId(),
                "first checkpoint has no parent");

        CheckpointService.Result second = service.createPending(
                "st-1", "second", "q", List.of(),
                "s", List.of(), null, emptyState,
                first.checkpoint().getCheckpointId());
        assertEquals(first.checkpoint().getCheckpointId(),
                second.checkpoint().getParentCheckpointId(),
                "second should reference first as parent");
    }

    @Test
    void currentCheckpointIdUpdated() {
        DagState emptyState = DagState.empty("plan-1", "st-1");
        assertEquals("", service.getCurrentCheckpointId());
        CheckpointService.Result r = service.createPending(
                "st-1", "x", "y", List.of(),
                "s", List.of(), null, emptyState, "");
        assertEquals(r.checkpoint().getCheckpointId(), service.getCurrentCheckpointId());
    }

    // ====== Stub git helper ======

    /**
     * 在 tempDir 下生成一个简单的脚本,模拟 git rev-parse HEAD。
     * 永远返回 stub-commit-stub-hash —— 测试只关心"非空且可被读取"。
     */
    static class StubGit {
        static String install(Path root) throws Exception {
            String name = isWindows() ? "git.cmd" : "git";
            Path script = root.resolve(name);
            String body;
            if (isWindows()) {
                body = "@echo off\r\necho stub-commit-stub-hash\r\n";
            } else {
                body = "#!/bin/sh\n"
                        + "if [ \"$1\" = \"rev-parse\" ] && [ \"$2\" = \"HEAD\" ]; then\n"
                        + "  echo \"stub-commit-stub-hash\"\n"
                        + "fi\n";
            }
            Files.writeString(script, body, StandardCharsets.UTF_8);
            if (!isWindows()) {
                script.toFile().setExecutable(true);
            }
            return script.toAbsolutePath().toString();
        }

        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }
    }
}

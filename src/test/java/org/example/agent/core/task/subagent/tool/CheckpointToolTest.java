package org.example.agent.core.task.subagent.tool;

import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.CheckpointService;
import org.example.agent.core.task.dag.DagNode;
import org.example.agent.core.task.dag.DagStateRepository;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CheckpointTool 阶段 4 测试。
 *
 * <p>手写 stub git —— 与 CheckpointServiceTest 同样的策略。
 * 验证：
 * <ul>
 *   <li>成功路径:返回 [checkpoint created] + id + manifest 路径</li>
 *   <li>无 active plan —— [error]</li>
 *   <li>empty reason —— [error]</li>
 *   <li>git 不可用 —— [error] git unavailable</li>
 *   <li>写入 manifest 的 status/seq/gitCommit</li>
 * </ul>
 */
class CheckpointToolTest {

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path sessionsRoot;
    private String stubGitBinary;
    private TaskOrchestrator orchestrator;
    private CheckpointService checkpointService;
    private CheckpointTool tool;

    @BeforeEach
    void setUp() throws Exception {
        projectRoot = tempDir.resolve("project");
        sessionsRoot = projectRoot.resolve(".agent").resolve("sessions");
        Files.createDirectories(sessionsRoot);
        stubGitBinary = installStubGit(projectRoot);

        DagStateRepository dagRepo = new DagStateRepository(sessionsRoot);
        dagRepo.init();
        SessionMessageStore sessionStore = new SessionMessageStore(sessionsRoot);
        LongTermStore longTermStore = new LongTermStore(projectRoot);
        orchestrator = new TaskOrchestrator(dagRepo, task -> null, new SyncTaskExecutor(), null);

        checkpointService = new CheckpointService(projectRoot, stubGitBinary,
                sessionStore, dagRepo, longTermStore, orchestrator);
        checkpointService.init();

        ToolDescriptorRegistry toolRegistry = new ToolDescriptorRegistry();
        tool = new CheckpointTool(checkpointService, orchestrator, toolRegistry);
    }

    @Test
    void checkpointWithoutActivePlanReturnsError() {
        String r = tool.checkpoint("pick-lib", "A or B?", null);
        assertTrue(r.contains("[error]"), "expected error; got: " + r);
        assertTrue(r.contains("active plan"), "error should mention active plan; got: " + r);
    }

    @Test
    void emptyReasonReturnsError() {
        orchestrator.createPlan("s-1", List.of(DagNode.pending("n-1", "n", "d", List.of(), "o")));
        String r = tool.checkpoint("", "question", null);
        assertTrue(r.contains("[error]"), "expected error; got: " + r);
    }

    @Test
    void gitUnavailableReturnsErrorAndNoManifest() {
        orchestrator.createPlan("s-1", List.of(DagNode.pending("n-1", "n", "d", List.of(), "o")));
        // 重新构造一个 git binary 指向不存在的脚本
        CheckpointService noGit = new CheckpointService(projectRoot,
                projectRoot.resolve("no-such-git").toString(),
                new SessionMessageStore(sessionsRoot),
                new DagStateRepository(sessionsRoot),
                new LongTermStore(projectRoot),
                orchestrator);
        noGit.init();
        CheckpointTool toolNoGit = new CheckpointTool(noGit, orchestrator,
                new ToolDescriptorRegistry());

        String r = toolNoGit.checkpoint("foo", "bar", null);
        assertTrue(r.contains("[error]"), "expected error; got: " + r);
        assertTrue(r.contains("git"), "error should mention git; got: " + r);
        assertFalse(Files.exists(sessionsRoot.resolve("s-1").resolve("checkpoints")),
                "no checkpoints dir should be created when git missing");
    }

    @Test
    void successfulCheckpointReturnsIdAndManifest() {
        orchestrator.createPlan("s-1",
                List.of(DagNode.pending("n-1", "node1", "d1", List.of(), "o1")));

        String r = tool.checkpoint("choose-cache", "redis or caffeine?", "src/cache/Foo.java,src/cache/Bar.java");
        assertTrue(r.contains("[checkpoint created]"), "got: " + r);
        assertTrue(r.contains("id=001-choose-cache"), "id should be 001-choose-cache; got: " + r);
        assertTrue(r.contains("manifest="), "manifest path should appear; got: " + r);

        // 真的写入了 manifest.json
        Path manifest = sessionsRoot.resolve("s-1").resolve("checkpoints")
                .resolve("001-choose-cache").resolve("manifest.json");
        assertTrue(Files.exists(manifest), "manifest.json should exist at " + manifest);

        // 加载回来校验内容
        Checkpoint loaded = checkpointService.load("s-1", "001-choose-cache").orElseThrow();
        assertEquals(Checkpoint.STATUS_PENDING_DECISION, loaded.getStatus());
        assertEquals("choose-cache", loaded.getReason());
        assertEquals("redis or caffeine?", loaded.getDecisionQuestion());
        assertEquals(List.of("src/cache/Foo.java", "src/cache/Bar.java"),
                loaded.getRelevantArtifacts());
        assertNotNull(loaded.getGitCommit());
        assertFalse(loaded.getGitCommit().isEmpty());
    }

    @Test
    void checkpointUpdatesCurrentCheckpointId() {
        orchestrator.createPlan("s-1", List.of(DagNode.pending("n-1", "n", "d", List.of(), "o")));
        assertEquals("", orchestrator.getCurrentCheckpointId());
        tool.checkpoint("first", "q", null);
        assertEquals("001-first", orchestrator.getCurrentCheckpointId());
    }

    @Test
    void secondCheckpointIncrementsSeq() {
        orchestrator.createPlan("s-1", List.of(DagNode.pending("n-1", "n", "d", List.of(), "o")));
        tool.checkpoint("first", "q1", null);
        tool.checkpoint("second", "q2", null);
        assertEquals("002-second", orchestrator.getCurrentCheckpointId());
        Checkpoint c1 = checkpointService.load("s-1", "001-first").orElseThrow();
        Checkpoint c2 = checkpointService.load("s-1", "002-second").orElseThrow();
        assertEquals(1, c1.getSeq());
        assertEquals(2, c2.getSeq());
        assertEquals("001-first", c2.getParentCheckpointId(),
                "second checkpoint should reference first as parent");
    }

    // ====== Stub git installer ======

    private static String installStubGit(Path root) throws Exception {
        String name = isWindows() ? "git.cmd" : "git";
        Path script = root.resolve(name);
        String body;
        if (isWindows()) {
            body = "@echo off\r\necho stub-commit-tool-test\r\n";
        } else {
            body = "#!/bin/sh\n"
                    + "if [ \"$1\" = \"rev-parse\" ] && [ \"$2\" = \"HEAD\" ]; then\n"
                    + "  echo \"stub-commit-tool-test\"\n"
                    + "fi\n";
        }
        Files.writeString(script, body, StandardCharsets.UTF_8);
        if (!isWindows()) {
            script.toFile().setExecutable(true);
        }
        return script.toAbsolutePath().toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

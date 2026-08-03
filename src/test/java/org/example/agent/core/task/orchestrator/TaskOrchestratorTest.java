package org.example.agent.core.task.orchestrator;

import org.example.agent.context.memory.MemoryIndex;
import org.example.agent.context.memory.MemoryIndexSynchronizer;
import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskSpec;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.event.PlanFinishedEvent;
import org.example.agent.core.task.event.SubTaskFailedEvent;
import org.example.agent.core.task.event.TaskEventPublisher;
import org.example.agent.core.task.event.TaskPlanCreatedEvent;
import org.example.agent.core.task.event.VerifyFailedEvent;
import org.example.agent.core.task.event.VerifyPassedEvent;
import org.example.agent.core.task.event.VerifyStartedEvent;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.example.agent.core.task.scheduler.TaskScheduler;
import org.example.agent.core.task.scheduler.TaskSchedulerException;
import org.example.agent.core.task.verify.VerifyCommandTemplate;
import org.example.agent.core.task.verify.VerifyResult;
import org.example.agent.core.task.verify.VerifyRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskOrchestrator 状态机测试（part5 §8.7 / §8.8）。
 */
class TaskOrchestratorTest {

    @TempDir
    Path tempDir;

    TaskPlanRepository repo;
    TaskScheduler scheduler;
    TaskEventPublisher publisher;
    VerifyRunner verifyRunner;
    TaskOrchestrator orchestrator;
    StubSubTaskExecutor executor;
    TaskOrchestratorConfig config;
    MemoryIndexSynchronizer indexSync;

    @BeforeEach
    void setUp() {
        repo = new TaskPlanRepository(tempDir);
        repo.init();
        scheduler = new TaskScheduler();
        publisher = new TaskEventPublisher();
        verifyRunner = new VerifyRunner();
        executor = new StubSubTaskExecutor();
        config = TaskOrchestratorConfig.builder().projectRoot(tempDir).build();
        indexSync = new MemoryIndexSynchronizer(
                new MemoryIndex(tempDir.resolve("MEMORY.md")), null, null, null, repo);
        orchestrator = new TaskOrchestrator(repo, scheduler, publisher, verifyRunner, executor, config, indexSync);
    }

    @Test
    @DisplayName("createPlan：分配 planId、补 taskId、落盘所有 SubTask，publish TaskPlanCreatedEvent")
    void createPlan_basicFlow() {
        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("step a").type(SubTaskType.IMPLEMENT).build(),
                SubTaskSpec.builder().title("step b").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-1")).build()
        );
        AtomicReference<TaskPlanCreatedEvent> captured = new AtomicReference<>();
        publisher.register(event -> {
            if (event instanceof TaskPlanCreatedEvent e) captured.set(e);
        });

        TaskPlan plan = orchestrator.createPlan("实现 X 功能", specs, "sess-1");

        assertNotNull(plan.getPlanId());
        assertEquals(TaskPlanStatus.ACTIVE, plan.getStatus());
        assertEquals(3, plan.getSubtaskIds().size()); // 2 + auto VERIFY
        assertEquals("sess-1", plan.getSessionId());
        assertEquals(3, repo.loadAllSubTasks(plan.getPlanId()).size());
        assertNotNull(captured.get());
        assertEquals(plan.getPlanId(), captured.get().getPlanId());
    }

    @Test
    @DisplayName("runActivePlan：3 个 IMPLEMENT + VERIFY 全成功 → plan COMPLETED + emit PlanFinishedEvent")
    void runActivePlan_happyPath() {
        executor.setDefaultOutcome(SubTaskOutcome.builder()
                .kind(SubTaskOutcome.Kind.COMPLETED_EXPLICIT)
                .note("done")
                .build());

        // verify 直接通过
        executor.setVerifyOverride((plan, sub) -> {
            // 让 VerifyRunner 跑个无害命令 —— 改成 "true"
            return null;
        });

        // 替换 verifyRunner，使用一个永远 exit 0 的模板
        VerifyRunner passingRunner = new VerifyRunner() {
            @Override
            public VerifyResult run(Path projectRoot, VerifyCommandTemplate template, Path logPath, long timeoutSeconds) {
                return VerifyResult.builder()
                        .exitCode(0)
                        .command(template == null ? "" : template.renderCommand())
                        .logTail("BUILD SUCCESS")
                        .build();
            }
        };
        TaskOrchestrator localOrch = new TaskOrchestrator(
                repo, scheduler, publisher, passingRunner, executor, config, indexSync);

        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("step a").type(SubTaskType.IMPLEMENT).build());
        localOrch.createPlan("happy goal", specs, "s1");

        AtomicReference<PlanFinishedEvent> finished = new AtomicReference<>();
        publisher.register(event -> {
            if (event instanceof PlanFinishedEvent e) finished.set(e);
        });

        localOrch.runActivePlan();

        assertEquals(TaskPlanStatus.COMPLETED, localOrch.activePlan().get().getStatus());
        assertNotNull(finished.get());
        assertEquals("COMPLETED", finished.get().getOutcome());
    }

    @Test
    @DisplayName("runActivePlan：VERIFY 失败 → 自动插入 FIX，VERIFY 二次失败 → plan ABANDONED")
    void runActivePlan_verifyFailureTriggersFix() {
        executor.setDefaultOutcome(SubTaskOutcome.builder()
                .kind(SubTaskOutcome.Kind.COMPLETED_EXPLICIT).build());

        AtomicReference<VerifyFailedEvent> firstFailed = new AtomicReference<>();
        AtomicInteger verifyCallCount = new AtomicInteger(0);
        VerifyRunner alwaysFailRunner = new VerifyRunner() {
            @Override
            public VerifyResult run(Path projectRoot, VerifyCommandTemplate template, Path logPath, long timeoutSeconds) {
                int n = verifyCallCount.getAndIncrement() + 1;
                return VerifyResult.builder()
                        .exitCode(1)
                        .command("mvn -q test")
                        .logTail("BUILD FAILURE")
                        .build();
            }
        };
        TaskOrchestrator localOrch = new TaskOrchestrator(
                repo, scheduler, publisher, alwaysFailRunner, executor, config, indexSync);
        publisher.register(event -> {
            if (event instanceof VerifyFailedEvent e) firstFailed.compareAndSet(null, e);
        });

        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("impl").type(SubTaskType.IMPLEMENT).build());
        localOrch.createPlan("verify-fails", specs, "s1");

        localOrch.runActivePlan();

        // 第一次失败 → emit VerifyFailedEvent
        assertNotNull(firstFailed.get());
        // 连续失败 2 次后 ABANDONED
        assertEquals(TaskPlanStatus.ABANDONED, localOrch.activePlan().get().getStatus());
        assertTrue(verifyCallCount.get() >= 2);
    }

    @Test
    @DisplayName("budget exhausted → SubTask FAILED，下游链式 SKIPPED")
    void runActivePlan_budgetExhaustedCascadesSkip() {
        // 第一个跑成功后失败，触发下游 SKIP
        executor.setOutcomes(java.util.Map.of(
                "st-1", SubTaskOutcome.builder().kind(SubTaskOutcome.Kind.COMPLETED_EXPLICIT).build(),
                "st-2", SubTaskOutcome.builder().kind(SubTaskOutcome.Kind.BUDGET_EXHAUSTED).build()
        ));

        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).build(),
                SubTaskSpec.builder().title("b").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-1")).build(),
                SubTaskSpec.builder().title("c").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-2")).build()
        );
        orchestrator.createPlan("g", specs, "s");
        orchestrator.runActivePlan();

        // st-3 应是 SKIPPED（因为 st-2 FAILED）
        Optional<SubTask> st3 = orchestrator.findSubTaskForRender("st-3");
        assertTrue(st3.isPresent());
        assertEquals(SubTaskStatus.SKIPPED, st3.get().getStatus());
    }

    @Test
    @DisplayName("markStarted 拒绝已 IN_PROGRESS 的 SubTask（状态机约束）")
    void markStarted_rejectsWrongState() {
        orchestrator.createPlan("g", List.of(SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).build()), "s");
        orchestrator.markStarted("st-1");
        assertThrows(TaskSchedulerException.class, () -> orchestrator.markStarted("st-1"));
    }

    @Test
    @DisplayName("VERIFY 子任务不可被 SKIPPED")
    void verifyCannotBeSkipped() {
        orchestrator.createPlan("verify", List.of(
                SubTaskSpec.builder().title("verify").type(SubTaskType.VERIFY).build()), "s");

        TaskSchedulerException ex = assertThrows(TaskSchedulerException.class,
                () -> orchestrator.markSkip("st-1", "not applicable"));

        assertTrue(ex.getMessage().contains("VERIFY"));
        assertEquals(SubTaskStatus.PENDING, orchestrator.requireSubTask("st-1").getStatus());
    }

    @Test
    @DisplayName("pause/resume：paused 标志持久化并可清除")
    void pauseResumePersistsPausedFlag() {
        TaskPlan plan = orchestrator.createPlan("pause me", List.of(
                SubTaskSpec.builder().title("a").type(SubTaskType.ANALYZE).build()), "s");

        orchestrator.pauseActivePlan();
        assertTrue(repo.loadPlan(plan.getPlanId()).orElseThrow().isPaused());

        orchestrator.resumeActivePlan();
        assertFalse(repo.loadPlan(plan.getPlanId()).orElseThrow().isPaused());
    }
    @Test
    @DisplayName("saveCheckpoint 追加手动 checkpoint，files/fields 一并写回 SubTask")
    void saveCheckpoint_appendsAndRefreshes() {
        orchestrator.createPlan("g", List.of(SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).build()), "s");
        orchestrator.markStarted("st-1");
        Checkpoint ck = orchestrator.saveCheckpoint("st-1",
                List.of("src/main/Foo.java"),
                List.of("Foo.bar"),
                "完成 Foo.bar 主体逻辑");
        assertEquals("ck-1", ck.getCheckpointId());
        assertFalse(ck.isAutomatic());

        SubTask refreshed = orchestrator.requireSubTask("st-1");
        assertEquals(1, refreshed.getCheckpoints().size());
        assertEquals(List.of("src/main/Foo.java"), refreshed.getCheckpoints().get(0).getFiles());
        assertEquals(List.of("Foo.bar"), refreshed.getCheckpoints().get(0).getFunctions());
    }

    @Test
    @DisplayName("adoptSubTask 替换 in-memory + 落盘")
    void adoptSubTask_persists() {
        orchestrator.createPlan("g", List.of(SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).build()), "s");
        SubTask sub = orchestrator.requireSubTask("st-1")
                .withProgress("进度 50%", "改 X 类", "下一步：加单元测试");
        orchestrator.adoptSubTask(sub);

        SubTask loaded = repo.loadSubTask(sub.getPlanId(), "st-1").orElseThrow();
        assertEquals("进度 50%", loaded.getDone());
        assertEquals("改 X 类", loaded.getCurrentAction());
        assertEquals("下一步：加单元测试", loaded.getNextStep());
    }

    @Test
    @DisplayName("abandonActivePlan 改 status 到 ABANDONED 并 emit PlanFinishedEvent")
    void abandonActivePlan() {
        orchestrator.createPlan("g", List.of(SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).build()), "s");
        AtomicReference<PlanFinishedEvent> captured = new AtomicReference<>();
        publisher.register(event -> {
            if (event instanceof PlanFinishedEvent e) captured.set(e);
        });

        orchestrator.abandonActivePlan("user gave up");

        assertEquals(TaskPlanStatus.ABANDONED, orchestrator.activePlan().get().getStatus());
        assertNotNull(captured.get());
        assertTrue(captured.get().getOutcome().contains("ABANDONED"));
    }

    @Test
    @DisplayName("queryPlanAsText 包含 planId、goal、每个 SubTask 状态")
    void queryPlanAsText_containsAllSubs() {
        orchestrator.createPlan("测试目标", List.of(SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).build()), "s");
        String text = orchestrator.queryPlanAsText(orchestrator.activePlan().get().getPlanId());
        assertTrue(text.contains("测试目标"));
        assertTrue(text.contains("st-1"));
        assertTrue(text.contains("st-2"));
        assertTrue(text.contains("0/3") || text.contains("0/2"));
    }

    // ============ 测试桩 ============

    static class StubSubTaskExecutor implements SubTaskExecutor {
        private final java.util.Map<String, SubTaskOutcome> outcomes = new java.util.HashMap<>();
        private SubTaskOutcome defaultOutcome = SubTaskOutcome.builder()
                .kind(SubTaskOutcome.Kind.COMPLETED_EXPLICIT)
                .note("ok")
                .build();

        void setDefaultOutcome(SubTaskOutcome o) { this.defaultOutcome = o; }
        void setOutcomes(java.util.Map<String, SubTaskOutcome> map) { this.outcomes.clear(); this.outcomes.putAll(map); }
        void setVerifyOverride(java.util.function.BiFunction<TaskPlan, SubTask, Void> ignored) { /* 兼容性占位 */ }

        @Override
        public SubTaskOutcome execute(TaskPlan plan, SubTask sub, TaskLoopObserver observer) {
            SubTaskOutcome o = outcomes.getOrDefault(sub.getTaskId(), defaultOutcome);
            // 模拟触发的副作用
            if (o.getKind() == SubTaskOutcome.Kind.COMPLETED_EXPLICIT) {
                observer.markCompleteSubtask(o.getNote());
            } else if (o.getKind() == SubTaskOutcome.Kind.FAILED_EXPLICIT) {
                observer.markFailSubtask(o.getFailureReason());
            } else if (o.getKind() == SubTaskOutcome.Kind.BUDGET_EXHAUSTED) {
                observer.markBudgetExhausted();
            } else if (o.getKind() == SubTaskOutcome.Kind.SKIPPED_EXPLICIT) {
                observer.markSkipSubtask(o.getFailureReason());
            }
            observer.touchedFiles().add("src/main/Stub_" + sub.getTaskId() + ".java");
            return o;
        }
    }
}
package org.example.agent.eval.recovery;

import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.PlanEdge;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度 C KPI：plan + SubTask 状态在进程重启后完整保留。
 * 对应 TEST.md §5.1 "C 维度 / C01+C02+C03 状态保持正确率"。
 *
 * <p>不直接调 ResumeCommand（需要 Spring 上下文）；用 TaskPlanRepository 的磁盘序列化 + 重新加载
 * 模拟"kill -9 后重启"，验证落地策略与状态机恢复正确。
 */
class C01_ResumeAfterSubTaskCompleteTest {

    @Test
    void planStateSurvivesProcessRestart(@TempDir Path tmp) {
        TaskPlanRepository repo = new TaskPlanRepository(tmp);
        Instant t0 = Instant.now();

        // ---- 1) 构造一个 3-SubTask 计划：A(VERIFIED) -> B(IN_PROGRESS) + C(PENDING) ----
        SubTask a = SubTask.builder()
                .taskId("A").planId("p1").title("实现 A")
                .type(SubTaskType.IMPLEMENT).status(SubTaskStatus.VERIFIED)
                .done("已实现 A 核心逻辑")
                .artifacts(List.of("src/X.java"))
                .createdAt(t0).startedAt(t0).completedAt(t0).attempts(1)
                .build();

        SubTask b = SubTask.builder()
                .taskId("B").planId("p1").title("实现 B")
                .type(SubTaskType.IMPLEMENT).status(SubTaskStatus.IN_PROGRESS)
                .dependsOn(List.of("A"))
                .currentAction("正在写 B 的核心方法")
                .nextStep("接着写 B 的边界处理")
                .checkpoints(List.of(
                        Checkpoint.manual("ck1", List.of("src/B.java"),
                                List.of("BService.handle"), "已加 B 框架")))
                .createdAt(t0).startedAt(t0).attempts(0)
                .build();

        SubTask c = SubTask.builder()
                .taskId("C").planId("p1").title("实现 C")
                .type(SubTaskType.IMPLEMENT).status(SubTaskStatus.PENDING)
                .dependsOn(List.of("A"))
                .createdAt(t0).attempts(0)
                .build();

        TaskPlan plan = TaskPlan.builder()
                .planId("p1").goal("实现 ABC 功能")
                .status(TaskPlanStatus.ACTIVE).paused(false)
                .currentTaskId("B")
                .subtaskIds(List.of("A", "B", "C"))
                .edges(List.of(new PlanEdge("A", "B"), new PlanEdge("A", "C")))
                .createdAt(t0).updatedAt(t0)
                .build();

        // ---- 2) 落盘 ----
        repo.savePlan(plan);
        repo.saveSubTask(a);
        repo.saveSubTask(b);
        repo.saveSubTask(c);

        // ---- 3) 模拟进程重启：全新 TaskPlanRepository 指向同一目录 ----
        TaskPlanRepository reborn = new TaskPlanRepository(tmp);

        // ---- 4) 重新加载并断言 ----
        TaskPlan loaded = reborn.loadPlan("p1").orElseThrow();

        // plan 顶层字段全部保留
        assertThat(loaded.getStatus()).isEqualTo(TaskPlanStatus.ACTIVE);
        assertThat(loaded.getCurrentTaskId()).isEqualTo("B");
        assertThat(loaded.isPaused()).isFalse();
        assertThat(loaded.getGoal()).isEqualTo("实现 ABC 功能");
        assertThat(loaded.getSubtaskIds()).containsExactly("A", "B", "C");
        assertThat(loaded.getEdges())
                .hasSize(2)
                .anyMatch(e -> e.from().equals("A") && e.to().equals("B"))
                .anyMatch(e -> e.from().equals("A") && e.to().equals("C"));

        // A 保持 VERIFIED 且不重跑
        SubTask loadedA = reborn.loadSubTask("p1", "A").orElseThrow();
        assertThat(loadedA.getStatus()).isEqualTo(SubTaskStatus.VERIFIED);
        assertThat(loadedA.getDone()).isEqualTo("已实现 A 核心逻辑");
        assertThat(loadedA.getArtifacts()).containsExactly("src/X.java");
        assertThat(loadedA.getAttempts()).isEqualTo(1);

        // B 保持 IN_PROGRESS；currentAction / nextStep / checkpoints 全部保留
        SubTask loadedB = reborn.loadSubTask("p1", "B").orElseThrow();
        assertThat(loadedB.getStatus()).isEqualTo(SubTaskStatus.IN_PROGRESS);
        assertThat(loadedB.getCurrentAction()).isEqualTo("正在写 B 的核心方法");
        assertThat(loadedB.getNextStep()).isEqualTo("接着写 B 的边界处理");
        assertThat(loadedB.getCheckpoints())
                .as("checkpoint array must survive serialization")
                .hasSize(1);
        assertThat(loadedB.getCheckpoints().get(0).getCheckpointId()).isEqualTo("ck1");
        assertThat(loadedB.getCheckpoints().get(0).getNote()).isEqualTo("已加 B 框架");
        assertThat(loadedB.getCheckpoints().get(0).getFunctions()).containsExactly("BService.handle");

        // C 保持 PENDING
        SubTask loadedC = reborn.loadSubTask("p1", "C").orElseThrow();
        assertThat(loadedC.getStatus()).isEqualTo(SubTaskStatus.PENDING);

        // KPI：所有 JSON 文件都在
        assertThat(reborn.planJsonPath("p1")).exists();
        assertThat(reborn.subtaskJsonPath("p1", "A")).exists();
        assertThat(reborn.subtaskJsonPath("p1", "B")).exists();
        assertThat(reborn.subtaskJsonPath("p1", "C")).exists();
    }

    @Test
    void updatedAtIsRefreshedOnStatusChange(@TempDir Path tmp) throws Exception {
        // 副断言：状态变更时 updatedAt 被刷新（用于 /tasks 按时间排序）
        TaskPlanRepository repo = new TaskPlanRepository(tmp);
        Instant t0 = Instant.now().minusSeconds(3600);

        SubTask b = SubTask.builder()
                .taskId("B").planId("p1").title("B").type(SubTaskType.IMPLEMENT)
                .status(SubTaskStatus.PENDING)
                .createdAt(t0).attempts(0).build();
        TaskPlan plan = TaskPlan.builder()
                .planId("p1").goal("g").status(TaskPlanStatus.ACTIVE)
                .currentTaskId("B").subtaskIds(List.of("B")).createdAt(t0).updatedAt(t0)
                .build();
        repo.savePlan(plan);
        repo.saveSubTask(b);

        // 变更状态：PENDING -> IN_PROGRESS
        TaskPlan next = plan.withCurrentTaskId("B");
        SubTask inProgress = b.withStatus(SubTaskStatus.IN_PROGRESS).withStartedAt(Instant.now());
        repo.savePlan(next);
        repo.saveSubTask(inProgress);

        TaskPlan loaded = new TaskPlanRepository(tmp).loadPlan("p1").orElseThrow();
        assertThat(loaded.getUpdatedAt())
                .as("updatedAt must advance after status change")
                .isAfter(t0);
    }
}
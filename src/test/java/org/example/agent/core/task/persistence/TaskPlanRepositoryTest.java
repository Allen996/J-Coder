package org.example.agent.core.task.persistence;

import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.PlanEdge;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.TaskPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskPlanRepository JSON 落盘测试（part5 §8.5）。
 */
class TaskPlanRepositoryTest {

    @TempDir
    Path tempDir;

    TaskPlanRepository repo;

    @BeforeEach
    void setUp() {
        repo = new TaskPlanRepository(tempDir);
        repo.init();
    }

    @Test
    @DisplayName("保存并加载 plan.json：字段完整")
    void saveAndLoadPlan_roundtrip() {
        TaskPlan plan = TaskPlan.builder()
                .planId("plan-abc")
                .goal("实现任务系统")
                .sessionId("sess-1")
                .status(TaskPlanStatus.ACTIVE)
                .subtaskIds(List.of("st-1", "st-2"))
                .edges(List.of(new PlanEdge("st-1", "st-2")))
                .build();
        repo.savePlan(plan);

        Optional<TaskPlan> loaded = repo.loadPlan("plan-abc");
        assertTrue(loaded.isPresent());
        TaskPlan p = loaded.get();
        assertEquals("plan-abc", p.getPlanId());
        assertEquals("实现任务系统", p.getGoal());
        assertEquals(TaskPlanStatus.ACTIVE, p.getStatus());
        assertEquals(2, p.getSubtaskIds().size());
        assertEquals(1, p.getEdges().size());
        assertEquals("st-1", p.getEdges().get(0).from());
        assertEquals("st-2", p.getEdges().get(0).to());
    }

    @Test
    @DisplayName("保存并加载 SubTask：包含 checkpoints 数组")
    void saveAndLoadSubTask_withCheckpoints() {
        SubTask sub = SubTask.builder()
                .taskId("st-1")
                .planId("plan-abc")
                .title("first")
                .type(SubTaskType.IMPLEMENT)
                .status(SubTaskStatus.IN_PROGRESS)
                .dependsOn(List.of())
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .attempts(1)
                .artifacts(new java.util.ArrayList<>(List.of("src/main/Foo.java")))
                .checkpoints(new java.util.ArrayList<>(List.of(
                        Checkpoint.automatic("ck-1", List.of("src/main/Foo.java"), "auto: 1 file"))))
                .build();
        repo.saveSubTask(sub);

        Optional<SubTask> loaded = repo.loadSubTask("plan-abc", "st-1");
        assertTrue(loaded.isPresent());
        SubTask s = loaded.get();
        assertEquals("first", s.getTitle());
        assertEquals(SubTaskType.IMPLEMENT, s.getType());
        assertEquals(1, s.getCheckpoints().size());
        assertEquals("ck-1", s.getCheckpoints().get(0).getCheckpointId());
        assertEquals(1, s.getArtifacts().size());
        assertEquals("src/main/Foo.java", s.getArtifacts().get(0));
    }

    @Test
    @DisplayName("loadAllSubTasks 扫描目录下所有 *.json 排除 plan.json")
    void loadAllSubTasks_filtersCorrectly() {
        for (int i = 1; i <= 3; i++) {
            repo.saveSubTask(SubTask.builder()
                    .taskId("st-" + i)
                    .planId("plan-abc")
                    .title("sub-" + i)
                    .type(SubTaskType.IMPLEMENT)
                    .status(SubTaskStatus.PENDING)
                    .dependsOn(List.of())
                    .attempts(0)
                    .artifacts(new java.util.ArrayList<>())
                    .checkpoints(new java.util.ArrayList<>())
                    .build());
        }
        repo.savePlan(TaskPlan.builder()
                .planId("plan-abc")
                .goal("g")
                .subtaskIds(List.of("st-1", "st-2", "st-3"))
                .build());

        List<SubTask> all = repo.loadAllSubTasks("plan-abc");
        assertEquals(3, all.size());
    }

    @Test
    @DisplayName("verify.log 落盘与读回")
    void verifyLog_writeAndRead() {
        repo.appendVerifyLog("plan-abc", "[mvn] BUILD FAILURE\nerror: ...\n");
        Optional<String> log = repo.readVerifyLog("plan-abc");
        assertTrue(log.isPresent());
        assertTrue(log.get().contains("BUILD FAILURE"));
    }

    @Test
    @DisplayName("listAllPlans 扫描所有 plan.json")
    void listAllPlans() {
        repo.savePlan(TaskPlan.builder().planId("plan-1").goal("g1").build());
        repo.savePlan(TaskPlan.builder().planId("plan-2").goal("g2").build());

        List<TaskPlan> plans = repo.listAllPlans();
        assertEquals(2, plans.size());
        assertTrue(plans.stream().anyMatch(p -> p.getPlanId().equals("plan-1")));
        assertTrue(plans.stream().anyMatch(p -> p.getPlanId().equals("plan-2")));
    }

    @Test
    @DisplayName("validateEdges 拒绝引用未声明节点的边")
    void validateEdges_rejectsUnknownRefs() {
        TaskPlan plan = TaskPlan.builder()
                .planId("plan-x")
                .goal("g")
                .subtaskIds(List.of("st-1"))
                .edges(List.of(new PlanEdge("ghost", "st-1")))
                .build();
        assertFalse(repo.validateEdges(plan));
    }

    @Test
    @DisplayName("删除 plan 移除整目录")
    void deletePlan_removesDirectory() {
        repo.savePlan(TaskPlan.builder().planId("plan-z").goal("z").build());
        repo.saveSubTask(SubTask.builder()
                .taskId("st-1").planId("plan-z").title("x").type(SubTaskType.IMPLEMENT)
                .status(SubTaskStatus.PENDING).dependsOn(List.of())
                .attempts(0).artifacts(new java.util.ArrayList<>()).checkpoints(new java.util.ArrayList<>())
                .build());

        assertTrue(repo.deletePlan("plan-z"));
        assertTrue(repo.loadPlan("plan-z").isEmpty());
        assertTrue(repo.loadSubTask("plan-z", "st-1").isEmpty());
    }
}
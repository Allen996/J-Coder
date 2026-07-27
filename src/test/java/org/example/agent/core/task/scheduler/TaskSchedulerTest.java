package org.example.agent.core.task.scheduler;

import org.example.agent.core.task.PlanEdge;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskSpec;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskScheduler 调度逻辑测试（part5 §8.4）。
 */
class TaskSchedulerTest {

    private final TaskScheduler scheduler = new TaskScheduler();

    @Test
    @DisplayName("DAG 拓扑校验：能产出合法 plan")
    void buildPlan_validDag() {
        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).dependsOn(List.of()).build(),
                SubTaskSpec.builder().title("b").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-1")).build(),
                SubTaskSpec.builder().title("c").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-2")).build()
        );
        TaskPlan plan = scheduler.buildPlan("plan-1", "goal", null, specs, false);

        assertEquals("plan-1", plan.getPlanId());
        assertEquals(3, plan.getSubtaskIds().size());
        assertEquals(2, plan.getEdges().size());
        assertTrue(plan.getEdges().contains(new PlanEdge("st-1", "st-2")));
        assertTrue(plan.getEdges().contains(new PlanEdge("st-2", "st-3")));
    }

    @Test
    @DisplayName("DAG 环检测：自环直接拒绝")
    void buildPlan_cycleRejected() {
        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-2")).build(),
                SubTaskSpec.builder().title("b").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-1")).build()
        );
        TaskSchedulerException ex = assertThrows(TaskSchedulerException.class,
                () -> scheduler.buildPlan("plan-x", "g", null, specs, false));
        assertTrue(ex.getMessage().toLowerCase().contains("cycle"));
    }

    @Test
    @DisplayName("依赖未声明的 taskId 拒绝")
    void buildPlan_unknownDepRejected() {
        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("a").type(SubTaskType.IMPLEMENT).dependsOn(List.of("ghost")).build()
        );
        TaskSchedulerException ex = assertThrows(TaskSchedulerException.class,
                () -> scheduler.buildPlan("plan-x", "g", null, specs, false));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    @DisplayName("IMPLEMENT plan 自动追加 VERIFY，依赖所有 IMPLEMENT/REFACTOR/FIX")
    void buildPlan_autoAppendVerify() {
        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("impl-1").type(SubTaskType.IMPLEMENT).build(),
                SubTaskSpec.builder().title("impl-2").type(SubTaskType.IMPLEMENT).dependsOn(List.of("st-1")).build()
        );
        TaskPlan plan = scheduler.buildPlan("plan-auto", "g", null, specs, true);

        assertEquals(3, plan.getSubtaskIds().size());
        assertEquals("st-3", plan.getSubtaskIds().get(2));
        // VERIFY 的 dependsOn 由 buildPlan 补全 → 应该有 st-1 → verify, st-2 → verify 两条边
        long verifyIn = plan.getEdges().stream()
                .filter(e -> e.to().equals("st-3"))
                .count();
        assertEquals(2, verifyIn);
    }

    @Test
    @DisplayName("LLM 自带 VERIFY 时不再追加")
    void buildPlan_respectsExplicitVerify() {
        List<SubTaskSpec> specs = List.of(
                SubTaskSpec.builder().title("impl").type(SubTaskType.IMPLEMENT).build(),
                SubTaskSpec.builder().title("verify").type(SubTaskType.VERIFY).dependsOn(List.of("st-1")).build()
        );
        TaskPlan plan = scheduler.buildPlan("plan-v", "g", null, specs, true);

        long verifyCount = plan.getSubtaskIds().stream()
                .map(s -> plan.getEdges())
                .filter(e -> true)
                .count();
        assertEquals(2, plan.getSubtaskIds().size());
    }

    @Test
    @DisplayName("selectNext 按拓扑序选 PENDING 且依赖已 VERIFIED 的子任务")
    void selectNext_returnsReady() {
        TaskPlan plan = TaskPlan.builder()
                .planId("p")
                .goal("g")
                .subtaskIds(List.of("st-1", "st-2"))
                .edges(List.of(new PlanEdge("st-1", "st-2")))
                .build();
        Map<String, SubTask> subs = new HashMap<>();
        subs.put("st-1", sub("st-1", SubTaskStatus.PENDING));
        subs.put("st-2", sub("st-2", SubTaskStatus.PENDING));

        var next = scheduler.selectNext(plan, subs);
        assertTrue(next.isPresent());
        assertEquals("st-1", next.get().getTaskId());

        // st-1 VERIFIED → st-2 可调度
        subs.put("st-1", sub("st-1", SubTaskStatus.VERIFIED));
        next = scheduler.selectNext(plan, subs);
        assertTrue(next.isPresent());
        assertEquals("st-2", next.get().getTaskId());
    }

    @Test
    @DisplayName("依赖未满足时 selectNext 跳过该 SubTask")
    void selectNext_skipsBlocked() {
        TaskPlan plan = TaskPlan.builder()
                .planId("p")
                .goal("g")
                .subtaskIds(List.of("st-1", "st-2"))
                .edges(List.of(new PlanEdge("st-1", "st-2")))
                .build();
        Map<String, SubTask> subs = new HashMap<>();
        subs.put("st-1", sub("st-1", SubTaskStatus.PENDING));
        subs.put("st-2", sub("st-2", SubTaskStatus.PENDING));

        // st-2 不能跑（依赖未 VERIFIED）
        assertFalse(scheduler.dependenciesReady(plan, subs, subs.get("st-2")));

        // 选到的应是 st-1
        var next = scheduler.selectNext(plan, subs);
        assertEquals("st-1", next.get().getTaskId());
    }

    private SubTask sub(String id, SubTaskStatus status) {
        return SubTask.builder()
                .taskId(id).planId("p").title(id).type(SubTaskType.IMPLEMENT)
                .status(status).dependsOn(List.of())
                .attempts(0)
                .artifacts(new java.util.ArrayList<>())
                .checkpoints(new java.util.ArrayList<>())
                .build();
    }
}
package org.example.agent.core.task.scheduler;

import org.example.agent.core.task.PlanEdge;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskSpec;
import org.example.agent.core.task.SubTaskStatus;
import org.example.agent.core.task.TaskPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DAG 校验 + 拓扑序调度（part5 §8.4）。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #validateSpec}：创建 plan 时校验 spec 列表的 DAG 无环、依赖在声明范围内</li>
 *   <li>{@link #validatePlan}：从已落盘的 TaskPlan 校验（可重复使用）</li>
 *   <li>{@link #selectNext}：从当前 SubTask 集合里选下一个可执行的 PENDING（依赖全部 VERIFIED）</li>
 * </ul>
 *
 * <p>v1 串行执行：同一 plan 内一次只跑一个 SubTask，所以 {@link #selectNext} 只返回 1 个。
 */
@Component
public class TaskScheduler {

    /**
     * 校验 spec 列表：分配 taskId、补全 edges、做 DAG 环检测、补一个默认 VERIFY 子任务。
     *
     * @param goal            计划目标（用于驱动默认 VERIFY 的 goal）
     * @param specs           LLM 给出的子任务列表
     * @param autoAddVerify   当 plan 含代码修改类子任务（IMPLEMENT/REFACTOR/FIX）时是否自动补一个 VERIFY
     * @return 经过校验、补全 taskId 与 edges 后的 TaskPlan（仍为 ACTIVE，调用方落盘）
     */
    public TaskPlan buildPlan(String planId,
                              String goal,
                              String sessionId,
                              List<SubTaskSpec> specs,
                              boolean autoAddVerify) {
        if (specs == null || specs.isEmpty()) {
            throw new TaskSchedulerException("plan must contain at least one subtask");
        }

        List<SubTaskSpec> working = new ArrayList<>(specs);
        boolean needsVerify = working.stream()
                .anyMatch(s -> s.getType() == org.example.agent.core.task.SubTaskType.IMPLEMENT
                        || s.getType() == org.example.agent.core.task.SubTaskType.REFACTOR
                        || s.getType() == org.example.agent.core.task.SubTaskType.FIX);
        boolean hasVerify = working.stream()
                .anyMatch(s -> s.getType() == org.example.agent.core.task.SubTaskType.VERIFY);

        // 自动追加 VERIFY（part5 §8.7 规则 1）
        if (needsVerify && !hasVerify && autoAddVerify) {
            working.add(SubTaskSpec.builder()
                    .title("VERIFY — run project build & tests")
                    .description("Run mvn/gradle/npm verification and confirm exit 0.")
                    .type(org.example.agent.core.task.SubTaskType.VERIFY)
                    .dependsOn(List.of()) // 校验后再补
                    .build());
        }

        // 1. 分配 taskId
        List<String> subtaskIds = new ArrayList<>();
        List<SubTaskSpec> indexed = new ArrayList<>(working.size());
        for (int i = 0; i < working.size(); i++) {
            SubTaskSpec spec = working.get(i);
            String id = "st-" + (i + 1);
            subtaskIds.add(id);
            indexed.add(spec);
        }

        // 2. 校验依赖索引在声明范围内
        Set<String> declared = new HashSet<>(subtaskIds);
        List<PlanEdge> edges = new ArrayList<>();
        for (int i = 0; i < indexed.size(); i++) {
            SubTaskSpec spec = indexed.get(i);
            String me = subtaskIds.get(i);
            for (String dep : spec.getDependsOn()) {
                if (dep == null || dep.isBlank()) continue;
                if (!declared.contains(dep)) {
                    throw new TaskSchedulerException(
                            "subtask[" + spec.getTitle() + "] dependsOn unknown id: " + dep
                                    + " (declared: " + declared + ")");
                }
                edges.add(new PlanEdge(dep, me));
            }
        }

        // 3. 自动追加的 VERIFY 任务需要把之前所有 IMPLEMENT/REFACTOR/FIX 都作为依赖
        //    （手动 VERIFY 任务 LLM 自己声明依赖）
        if (needsVerify && !hasVerify && autoAddVerify) {
            int verifyIdx = indexed.size() - 1;
            String verifyId = subtaskIds.get(verifyIdx);
            for (int i = 0; i < verifyIdx; i++) {
                SubTaskSpec s = indexed.get(i);
                if (s.getType() == org.example.agent.core.task.SubTaskType.IMPLEMENT
                        || s.getType() == org.example.agent.core.task.SubTaskType.REFACTOR
                        || s.getType() == org.example.agent.core.task.SubTaskType.FIX) {
                    edges.add(new PlanEdge(subtaskIds.get(i), verifyId));
                }
            }
        }

        // 4. DAG 环检测 —— 用 Kahn 算法做拓扑排序，若拓扑序长度 != 节点数则有环
        detectCycle(subtaskIds, edges);

        return TaskPlan.builder()
                .planId(planId)
                .goal(goal)
                .sessionId(sessionId)
                .status(org.example.agent.core.task.TaskPlanStatus.ACTIVE)
                .currentTaskId(null)
                .paused(false)
                .subtaskIds(subtaskIds)
                .edges(edges)
                .build();
    }

    /**
     * 把 TaskPlan 的 subtaskIds 与 edges 重新做一次环检测（用于从磁盘恢复的 plan 再校验）。
     */
    public void validatePlan(TaskPlan plan) {
        if (plan == null) throw new TaskSchedulerException("plan is null");
        detectCycle(plan.getSubtaskIds(), plan.getEdges());
    }

    /**
     * 选下一个可执行的 SubTask（part5 §8.4 调度规则）。
     *
     * <p>规则：
     * <ul>
     *   <li>状态为 PENDING 或 FAILED（重试）</li>
     *   <li>所有依赖 status == VERIFIED（重试时旧依赖仍是 VERIFIED）</li>
     *   <li>按 plan 的 subtaskIds 顺序找第一个满足的（拓扑序粗略近似）</li>
     * </ul>
     *
     * @return 可执行的 SubTask，没有则返回 Optional.empty()
     */
    public Optional<SubTask> selectNext(TaskPlan plan, Map<String, SubTask> subtasks) {
        if (plan == null || subtasks == null) return Optional.empty();
        for (String taskId : plan.getSubtaskIds()) {
            SubTask sub = subtasks.get(taskId);
            if (sub == null) continue;
            if (sub.getStatus() != SubTaskStatus.PENDING) {
                continue;
            }
            if (dependenciesReady(plan, subtasks, sub)) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }

    /**
     * 给定 SubTask 是否依赖都已 VERIFIED。依赖以 plan 的 DAG 边为准（单一真相），
     * 而非 SubTask.dependsOn（自动补的 VERIFY 其 dependsOn 为空，依赖只体现在边上）。
     */
    public boolean dependenciesReady(TaskPlan plan, Map<String, SubTask> subtasks, SubTask sub) {
        List<String> preds = plan.predecessorsOf(sub.getTaskId());
        if (preds.isEmpty()) return true;
        for (String dep : preds) {
            SubTask upstream = subtasks.get(dep);
            if (upstream == null) return false;
            if (upstream.getStatus() != SubTaskStatus.VERIFIED) return false;
        }
        return true;
    }

    /**
     * Kahn 算法环检测。拓扑序长度 != 节点数 → 有环。
     */
    private void detectCycle(List<String> subtaskIds, List<PlanEdge> edges) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : subtaskIds) inDegree.put(id, 0);
        for (PlanEdge e : edges) {
            if (!inDegree.containsKey(e.from()) || !inDegree.containsKey(e.to())) {
                throw new TaskSchedulerException(
                        "edge references undeclared node: " + e.from() + " -> " + e.to());
            }
            inDegree.merge(e.to(), 1, Integer::sum);
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (PlanEdge e : edges) {
                if (e.from().equals(node)) {
                    int newDeg = inDegree.merge(e.to(), -1, Integer::sum);
                    if (newDeg == 0) queue.add(e.to());
                }
            }
        }
        if (visited != subtaskIds.size()) {
            throw new TaskSchedulerException(
                    "plan DAG contains cycle: visited=" + visited + ", total=" + subtaskIds.size());
        }
    }
}
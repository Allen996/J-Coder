package org.example.agent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * TaskPlan 聚合根（part5 §8.5 plan.json）。
 *
 * <p>持有 DAG 与 SubTask 列表，提供状态查询与不可变快照。
 * 计划一旦创建即冻结 —— 中途调整只能通过 §8.7 的 FIX 局部插入。
 */
@Getter
@Builder
@ToString(of = {"planId", "goal", "status", "paused"})
public final class TaskPlan {

    @JsonProperty("planId")
    private final String planId;

    @JsonProperty("goal")
    private final String goal;

    @JsonProperty("sessionId")
    private final String sessionId;

    @JsonProperty("createdAt")
    private final Instant createdAt;

    @JsonProperty("updatedAt")
    private final Instant updatedAt;

    @JsonProperty("status")
    private final TaskPlanStatus status;

    @JsonProperty("currentTaskId")
    private final String currentTaskId;

    @JsonProperty("paused")
    private final boolean paused;

    @JsonProperty("subtaskIds")
    private final List<String> subtaskIds;

    @JsonProperty("edges")
    private final List<PlanEdge> edges;

    @JsonProperty("verifyAttempts")
    private final int verifyAttempts;

    @JsonCreator
    public TaskPlan(
            @JsonProperty("planId") String planId,
            @JsonProperty("goal") String goal,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("status") TaskPlanStatus status,
            @JsonProperty("currentTaskId") String currentTaskId,
            @JsonProperty("paused") boolean paused,
            @JsonProperty("subtaskIds") List<String> subtaskIds,
            @JsonProperty("edges") List<PlanEdge> edges,
            @JsonProperty("verifyAttempts") int verifyAttempts) {
        this.planId = planId;
        this.goal = goal;
        this.sessionId = sessionId;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
        this.status = status == null ? TaskPlanStatus.ACTIVE : status;
        this.currentTaskId = currentTaskId;
        this.paused = paused;
        this.subtaskIds = subtaskIds == null ? new ArrayList<>() : new ArrayList<>(subtaskIds);
        this.edges = edges == null ? new ArrayList<>() : new ArrayList<>(edges);
        this.verifyAttempts = Math.max(0, verifyAttempts);
    }

    public TaskPlan withStatus(TaskPlanStatus newStatus) {
        return TaskPlan.builder()
                .planId(planId)
                .goal(goal)
                .sessionId(sessionId)
                .createdAt(createdAt)
                .updatedAt(Instant.now())
                .status(newStatus)
                .currentTaskId(currentTaskId)
                .paused(paused)
                .subtaskIds(subtaskIds)
                .edges(edges)
                .verifyAttempts(verifyAttempts)
                .build();
    }

    public TaskPlan withCurrentTaskId(String newCurrentTaskId) {
        return TaskPlan.builder()
                .planId(planId)
                .goal(goal)
                .sessionId(sessionId)
                .createdAt(createdAt)
                .updatedAt(Instant.now())
                .status(status)
                .currentTaskId(newCurrentTaskId)
                .paused(paused)
                .subtaskIds(subtaskIds)
                .edges(edges)
                .verifyAttempts(verifyAttempts)
                .build();
    }

    public TaskPlan withPaused(boolean newPaused) {
        return TaskPlan.builder()
                .planId(planId)
                .goal(goal)
                .sessionId(sessionId)
                .createdAt(createdAt)
                .updatedAt(Instant.now())
                .status(status)
                .currentTaskId(currentTaskId)
                .paused(newPaused)
                .subtaskIds(subtaskIds)
                .edges(edges)
                .verifyAttempts(verifyAttempts)
                .build();
    }

    public TaskPlan withEdgeInserted(String newSubTaskId, List<String> parentsOf,
                                     List<String> childrenOf, List<String> subtaskIds) {
        List<PlanEdge> newEdges = new ArrayList<>(edges);
        newEdges.removeIf(e -> childrenOf.contains(e.from()) && e.to().equals(newSubTaskId));
        for (String parent : parentsOf) {
            newEdges.add(new PlanEdge(parent, newSubTaskId));
        }
        for (String child : childrenOf) {
            newEdges.add(new PlanEdge(newSubTaskId, child));
        }
        return TaskPlan.builder()
                .planId(planId)
                .goal(goal)
                .sessionId(sessionId)
                .createdAt(createdAt)
                .updatedAt(Instant.now())
                .status(status)
                .currentTaskId(currentTaskId)
                .paused(paused)
                .subtaskIds(subtaskIds)
                .edges(newEdges)
                .verifyAttempts(verifyAttempts)
                .build();
    }

    public TaskPlan withVerifyAttempts(int newAttempts) {
        return TaskPlan.builder()
                .planId(planId)
                .goal(goal)
                .sessionId(sessionId)
                .createdAt(createdAt)
                .updatedAt(Instant.now())
                .status(status)
                .currentTaskId(currentTaskId)
                .paused(paused)
                .subtaskIds(subtaskIds)
                .edges(edges)
                .verifyAttempts(newAttempts)
                .build();
    }

    public List<String> predecessorsOf(String taskId) {
        List<String> result = new ArrayList<>();
        for (PlanEdge e : edges) {
            if (e.to().equals(taskId)) {
                result.add(e.from());
            }
        }
        return result;
    }

    public List<String> successorsOf(String taskId) {
        List<String> result = new ArrayList<>();
        for (PlanEdge e : edges) {
            if (e.from().equals(taskId)) {
                result.add(e.to());
            }
        }
        return result;
    }
}

package org.example.agent.core.task.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAG 静态结构 —— 阶段 2 引入，持久化为 plan.json 的"初始计划书"。
 *
 * <p>仅持有静态字段（taskId/title/description/dependsOn/expectedOutput）。
 * 运行时状态（state/attempts/lastResult）由 {@link DagState} 持有。
 *
 * <p>{@code runtimeFieldsIgnored=true} 让 Jackson 反序列化时忽略运行时字段（防止旧 plan.json
 * 含 state/attempts 等字段时反序列化失败）。
 */
@Getter
@Builder
@ToString(of = {"planId", "goal", "sessionId"})
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DagGraph {

    @JsonProperty("planId")
    private final String planId;

    @JsonProperty("goal")
    private final String goal;

    @JsonProperty("sessionId")
    private final String sessionId;

    @JsonProperty("createdAt")
    private final Instant createdAt;

    /** taskId → 节点。LinkedHashMap 保持插入顺序,便于调试。 */
    @JsonProperty("nodes")
    private final Map<String, DagNode> nodes;

    @JsonCreator
    public DagGraph(
            @JsonProperty("planId") String planId,
            @JsonProperty("goal") String goal,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("nodes") Map<String, DagNode> nodes) {
        this.planId = planId == null ? "" : planId;
        this.goal = goal == null ? "" : goal;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.nodes = nodes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(nodes);
    }

    public static DagGraph empty(String planId, String goal, String sessionId) {
        return new DagGraph(planId, goal, sessionId, Instant.now(), new LinkedHashMap<>());
    }

    public int size() {
        return nodes == null ? 0 : nodes.size();
    }

    public Set<String> taskIds() {
        return nodes == null ? Set.of() : nodes.keySet();
    }

    public DagNode get(String taskId) {
        return nodes == null ? null : nodes.get(taskId);
    }

    /** 后继节点 —— 依赖本节点的所有节点。 */
    public List<String> successorsOf(String taskId) {
        List<String> result = new ArrayList<>();
        if (nodes == null) return result;
        for (Map.Entry<String, DagNode> e : nodes.entrySet()) {
            List<String> deps = e.getValue().dependencies();
            if (deps.contains(taskId)) result.add(e.getKey());
        }
        return result;
    }

    /** 前驱节点 —— 本节点依赖的所有节点。 */
    public List<String> predecessorsOf(String taskId) {
        DagNode n = nodes == null ? null : nodes.get(taskId);
        return n == null ? List.of() : n.dependencies();
    }

    /**
     * 添加节点（仅 plan 写入阶段使用，状态始终 PENDING）。
     * 校验：taskId 不重复；依赖节点都已存在。
     */
    public DagGraph withNodeAdded(DagNode node) {
        if (nodes.containsKey(node.getTaskId())) {
            throw new IllegalArgumentException("taskId already exists: " + node.getTaskId());
        }
        for (String dep : node.dependencies()) {
            if (!nodes.containsKey(dep)) {
                throw new IllegalArgumentException(
                        "taskId=" + node.getTaskId() + " depends on missing taskId=" + dep);
            }
        }
        Map<String, DagNode> next = new LinkedHashMap<>(nodes);
        next.put(node.getTaskId(), node);
        return new DagGraph(planId, goal, sessionId, createdAt, next);
    }

    public DagGraph withNodes(Collection<DagNode> newNodes) {
        Map<String, DagNode> next = new LinkedHashMap<>(nodes);
        for (DagNode n : newNodes) {
            if (next.containsKey(n.getTaskId())) {
                throw new IllegalArgumentException("taskId already exists: " + n.getTaskId());
            }
            for (String dep : n.dependencies()) {
                if (!next.containsKey(dep)) {
                    throw new IllegalArgumentException(
                            "taskId=" + n.getTaskId() + " depends on missing taskId=" + dep);
                }
            }
            next.put(n.getTaskId(), n);
        }
        return new DagGraph(planId, goal, sessionId, createdAt, next);
    }
}
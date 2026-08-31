package org.example.agent.core.task.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DAG 运行时状态 —— 阶段 2 引入，持久化为 dag-state.json。
 *
 * <p>运行时字段（state/attempts/startedAt/completedAt/lastResult）按 taskId 持有。
 * 持久化策略（M.3）：主 loop 每轮推理前批量落盘一次。
 *
 * <p>{@code staticFieldsIgnored=true} 让 Jackson 反序列化时忽略静态字段
（taskId/title/description/dependsOn/expectedOutput）—— 这些字段由 {@link DagGraph} 持有。
 */
@Getter
@Builder
@ToString(of = {"planId", "sessionId", "status"})
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DagState {

    @JsonProperty("planId")
    private final String planId;

    @JsonProperty("sessionId")
    private final String sessionId;

    /** 整体 plan 状态：RUNNING / COMPLETED / FAILED / ABANDONED。 */
    @JsonProperty("status")
    private final DagPlanStatus status;

    @JsonProperty("updatedAt")
    private final Instant updatedAt;

    /** taskId → 节点运行时状态。LinkedHashMap 保持插入顺序。 */
    @JsonProperty("runtime")
    private final Map<String, DagNode> runtime;

    @JsonCreator
    public DagState(
            @JsonProperty("planId") String planId,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("status") DagPlanStatus status,
            @JsonProperty("updatedAt") Instant updatedAt,
            @JsonProperty("runtime") Map<String, DagNode> runtime) {
        this.planId = planId == null ? "" : planId;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.status = status == null ? DagPlanStatus.RUNNING : status;
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        this.runtime = runtime == null ? new LinkedHashMap<>() : new LinkedHashMap<>(runtime);
    }

    public static DagState empty(String planId, String sessionId) {
        return new DagState(planId, sessionId, DagPlanStatus.RUNNING, Instant.now(), new LinkedHashMap<>());
    }

    public DagNode get(String taskId) {
        return runtime == null ? null : runtime.get(taskId);
    }

    public DagState withStatus(DagPlanStatus newStatus) {
        return new DagState(planId, sessionId, newStatus, Instant.now(), runtime);
    }

    public DagState withNodeUpdated(DagNode node) {
        Map<String, DagNode> next = new LinkedHashMap<>(runtime);
        next.put(node.getTaskId(), node);
        return new DagState(planId, sessionId, status, Instant.now(), next);
    }

    /** 批量更新节点（主 loop 一次性落盘用）。 */
    public DagState withNodesUpdated(Map<String, DagNode> updates) {
        Map<String, DagNode> next = new LinkedHashMap<>(runtime);
        next.putAll(updates);
        return new DagState(planId, sessionId, status, Instant.now(), next);
    }

    public int size() {
        return runtime == null ? 0 : runtime.size();
    }
}
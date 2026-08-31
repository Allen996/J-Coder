package org.example.agent.core.task.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DAG 节点 —— 阶段 2 引入，替换旧 {@code SubTask}（不再持久化为 {taskId}.json）。
 *
 * <p>字段分组：
 * <ul>
 *   <li>静态（plan.json 持有）：taskId / title / description / dependsOn / expectedOutput</li>
 *   <li>运行时（dag-state.json 持有）：state / attempts / startedAt / completedAt / lastResult</li>
 * </ul>
 *
 * <p>序列化在两个文件之间共享字段 —— {@code DagGraph} 持久化静态部分，
 * {@code DagState} 持久化运行时部分。两边各自 {@code @JsonIgnoreProperties(ignoreUnknown=true)}
 * 以容忍独立读写时的字段缺失。
 */
@Getter
@Builder
@ToString(of = {"taskId", "title", "state"})
public final class DagNode {

    // ===== 静态字段（plan.json）=====

    @JsonProperty("taskId")
    private final String taskId;

    @JsonProperty("title")
    private final String title;

    @JsonProperty("description")
    private final String description;

    @JsonProperty("dependsOn")
    private final List<String> dependsOn;

    @JsonProperty("expectedOutput")
    private final String expectedOutput;

    // ===== 运行时字段（dag-state.json）=====

    @JsonProperty("state")
    private final DagNodeState state;

    @JsonProperty("attempts")
    private final int attempts;

    @JsonProperty("startedAt")
    private final Instant startedAt;

    @JsonProperty("completedAt")
    private final Instant completedAt;

    /** 最后一次执行的结果摘要（成功 report / 失败 reason / 超时 reason）。 */
    @JsonProperty("lastResult")
    private final NodeResult lastResult;

    @JsonCreator
    public DagNode(
            @JsonProperty("taskId") String taskId,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("dependsOn") List<String> dependsOn,
            @JsonProperty("expectedOutput") String expectedOutput,
            @JsonProperty("state") DagNodeState state,
            @JsonProperty("attempts") int attempts,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("completedAt") Instant completedAt,
            @JsonProperty("lastResult") NodeResult lastResult) {
        this.taskId = taskId == null ? "" : taskId;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        this.expectedOutput = expectedOutput == null ? "" : expectedOutput;
        this.state = state == null ? DagNodeState.PENDING : state;
        this.attempts = Math.max(0, attempts);
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.lastResult = lastResult;
    }

    /** 构造一个 PENDING 状态的静态节点（plan 创建时用）。 */
    public static DagNode pending(String taskId, String title, String description,
                                  List<String> dependsOn, String expectedOutput) {
        return DagNode.builder()
                .taskId(taskId)
                .title(title)
                .description(description == null ? "" : description)
                .dependsOn(dependsOn == null ? List.of() : List.copyOf(dependsOn))
                .expectedOutput(expectedOutput == null ? "" : expectedOutput)
                .state(DagNodeState.PENDING)
                .attempts(0)
                .build();
    }

    public boolean isTerminal() {
        return state != null && state.isTerminal();
    }

    public boolean isSuccess() {
        return state == DagNodeState.COMPLETED;
    }

    public DagNode withState(DagNodeState newState) {
        return DagNode.builder()
                .taskId(taskId).title(title).description(description)
                .dependsOn(dependsOn).expectedOutput(expectedOutput)
                .state(newState).attempts(attempts)
                .startedAt(startedAt).completedAt(completedAt)
                .lastResult(lastResult)
                .build();
    }

    public DagNode withAttempts(int newAttempts) {
        return DagNode.builder()
                .taskId(taskId).title(title).description(description)
                .dependsOn(dependsOn).expectedOutput(expectedOutput)
                .state(state).attempts(newAttempts)
                .startedAt(startedAt).completedAt(completedAt)
                .lastResult(lastResult)
                .build();
    }

    public DagNode withStartedAt(Instant ts) {
        return DagNode.builder()
                .taskId(taskId).title(title).description(description)
                .dependsOn(dependsOn).expectedOutput(expectedOutput)
                .state(state).attempts(attempts)
                .startedAt(ts).completedAt(completedAt)
                .lastResult(lastResult)
                .build();
    }

    public DagNode withCompletedAt(Instant ts) {
        return DagNode.builder()
                .taskId(taskId).title(title).description(description)
                .dependsOn(dependsOn).expectedOutput(expectedOutput)
                .state(state).attempts(attempts)
                .startedAt(startedAt).completedAt(ts)
                .lastResult(lastResult)
                .build();
    }

    public DagNode withLastResult(NodeResult result) {
        return DagNode.builder()
                .taskId(taskId).title(title).description(description)
                .dependsOn(dependsOn).expectedOutput(expectedOutput)
                .state(state).attempts(attempts)
                .startedAt(startedAt).completedAt(completedAt)
                .lastResult(result)
                .build();
    }

    public List<String> dependencies() {
        return dependsOn == null ? List.of() : dependsOn;
    }

    /** 一个简单的 holder —— 用于序列化 lastResult 嵌套字段。 */
    @Getter
    @Builder
    @ToString
    public static final class NodeResult {
        @JsonProperty("status")
        private final String status;           // COMPLETED / FAILED / TIMEOUT
        @JsonProperty("report")
        private final String report;           // final report 全文（阶段 3 接通截断）
        @JsonProperty("reason")
        private final String reason;           // 失败/超时原因
        @JsonProperty("artifacts")
        private final List<String> artifacts;  // 创建/修改的文件
        @JsonProperty("durationMs")
        private final long durationMs;

        @JsonCreator
        public NodeResult(
                @JsonProperty("status") String status,
                @JsonProperty("report") String report,
                @JsonProperty("reason") String reason,
                @JsonProperty("artifacts") List<String> artifacts,
                @JsonProperty("durationMs") long durationMs) {
            this.status = status == null ? "" : status;
            this.report = report == null ? "" : report;
            this.reason = reason == null ? "" : reason;
            this.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
            this.durationMs = Math.max(0L, durationMs);
        }
    }
}
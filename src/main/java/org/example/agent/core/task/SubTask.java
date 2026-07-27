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
 * SubTask 实体（part5 §8.5 {taskId}.json）。
 *
 * <p>围绕"这个子任务现在处于什么状态、做到哪了、接下来干什么"组织：
 * <ul>
 *   <li>{@code done}: 已经落地的工作摘要（自然语言一段）</li>
 *   <li>{@code currentAction}: 当前正在进行的动作（IN_PROGRESS 时实时更新）</li>
 *   <li>{@code nextStep}: LLM 规划的下一个动作，供中断恢复时接续</li>
 *   <li>{@code checkpoints}: 恢复用的进度快照数组</li>
 * </ul>
 *
 * <p>状态机由 {@link SubTaskStatus} 描述，转换约束见 part5 §8.3。
 */
@Getter
@Builder
@ToString(of = {"taskId", "title", "type", "status", "dependsOn"})
public final class SubTask {

    @JsonProperty("taskId")
    private final String taskId;

    @JsonProperty("planId")
    private final String planId;

    @JsonProperty("title")
    private final String title;

    @JsonProperty("description")
    private final String description;

    @JsonProperty("type")
    private final SubTaskType type;

    @JsonProperty("status")
    private final SubTaskStatus status;

    @JsonProperty("dependsOn")
    private final List<String> dependsOn;

    @JsonProperty("done")
    private final String done;

    @JsonProperty("currentAction")
    private final String currentAction;

    @JsonProperty("nextStep")
    private final String nextStep;

    @JsonProperty("createdAt")
    private final Instant createdAt;

    @JsonProperty("startedAt")
    private final Instant startedAt;

    @JsonProperty("completedAt")
    private final Instant completedAt;

    @JsonProperty("attempts")
    private final int attempts;

    @JsonProperty("failureReason")
    private final String failureReason;

    @JsonProperty("artifacts")
    private final List<String> artifacts;

    @JsonProperty("checkpoints")
    private final List<Checkpoint> checkpoints;

    @JsonProperty("maxSteps")
    private final Integer maxSteps;

    @JsonCreator
    public SubTask(
            @JsonProperty("taskId") String taskId,
            @JsonProperty("planId") String planId,
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("type") SubTaskType type,
            @JsonProperty("status") SubTaskStatus status,
            @JsonProperty("dependsOn") List<String> dependsOn,
            @JsonProperty("done") String done,
            @JsonProperty("currentAction") String currentAction,
            @JsonProperty("nextStep") String nextStep,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("completedAt") Instant completedAt,
            @JsonProperty("attempts") int attempts,
            @JsonProperty("failureReason") String failureReason,
            @JsonProperty("artifacts") List<String> artifacts,
            @JsonProperty("checkpoints") List<Checkpoint> checkpoints,
            @JsonProperty("maxSteps") Integer maxSteps) {
        this.taskId = taskId;
        this.planId = planId;
        this.title = title;
        this.description = description;
        this.type = type;
        this.status = status == null ? SubTaskStatus.PENDING : status;
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        this.done = done;
        this.currentAction = currentAction;
        this.nextStep = nextStep;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.attempts = Math.max(0, attempts);
        this.failureReason = failureReason;
        this.artifacts = artifacts == null ? new ArrayList<>() : new ArrayList<>(artifacts);
        this.checkpoints = checkpoints == null ? new ArrayList<>() : new ArrayList<>(checkpoints);
        this.maxSteps = maxSteps;
    }

    public SubTask withStatus(SubTaskStatus newStatus) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(newStatus)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .attempts(attempts)
                .failureReason(failureReason)
                .artifacts(artifacts)
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withProgress(String newDone, String newCurrentAction, String newNextStep) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(newDone)
                .currentAction(newCurrentAction)
                .nextStep(newNextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .attempts(attempts)
                .failureReason(failureReason)
                .artifacts(artifacts)
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withCheckpoint(Checkpoint checkpoint) {
        List<Checkpoint> next = new ArrayList<>(checkpoints);
        next.add(checkpoint);
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .attempts(attempts)
                .failureReason(failureReason)
                .artifacts(artifacts)
                .checkpoints(next)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withArtifacts(List<String> newArtifacts) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .attempts(attempts)
                .failureReason(failureReason)
                .artifacts(newArtifacts == null ? new ArrayList<>() : new ArrayList<>(newArtifacts))
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withFailureReason(String reason) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .attempts(attempts)
                .failureReason(reason)
                .artifacts(artifacts)
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withStartedAt(Instant ts) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(ts)
                .completedAt(completedAt)
                .attempts(attempts)
                .failureReason(failureReason)
                .artifacts(artifacts)
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withCompletedAt(Instant ts) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(ts)
                .attempts(attempts)
                .failureReason(failureReason)
                .artifacts(artifacts)
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public SubTask withAttempts(int newAttempts) {
        return SubTask.builder()
                .taskId(taskId)
                .planId(planId)
                .title(title)
                .description(description)
                .type(type)
                .status(status)
                .dependsOn(dependsOn)
                .done(done)
                .currentAction(currentAction)
                .nextStep(nextStep)
                .createdAt(createdAt)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .attempts(newAttempts)
                .failureReason(failureReason)
                .artifacts(artifacts)
                .checkpoints(checkpoints)
                .maxSteps(maxSteps)
                .build();
    }

    public Checkpoint lastCheckpoint() {
        if (checkpoints == null || checkpoints.isEmpty()) return null;
        return checkpoints.get(checkpoints.size() - 1);
    }
}

package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public final class SubTaskFailedEvent implements TaskEvent {
    private final String planId;
    private final String taskId;
    private final Instant at;
    private final String reason;
    private final int attempts;

    @JsonCreator
    public SubTaskFailedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("at") Instant at,
            @JsonProperty("reason") String reason,
            @JsonProperty("attempts") int attempts) {
        this.planId = planId;
        this.taskId = taskId;
        this.at = at == null ? Instant.now() : at;
        this.reason = reason;
        this.attempts = attempts;
    }

    @Override
    public String getType() {
        return "subtask.failed";
    }
}
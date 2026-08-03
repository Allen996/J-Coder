package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public final class SubTaskCompletedEvent implements TaskEvent {
    private final String planId;
    private final String taskId;
    private final Instant at;
    private final int attempts;
    private final String done;

    @JsonCreator
    public SubTaskCompletedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("at") Instant at,
            @JsonProperty("attempts") int attempts,
            @JsonProperty("done") String done) {
        this.planId = planId;
        this.taskId = taskId;
        this.at = at == null ? Instant.now() : at;
        this.attempts = attempts;
        this.done = done;
    }

    @Override
    public String getType() {
        return "subtask.completed";
    }
}
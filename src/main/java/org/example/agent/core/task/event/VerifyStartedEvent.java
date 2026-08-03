package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public final class VerifyStartedEvent implements TaskEvent {
    private final String planId;
    private final String taskId;
    private final Instant at;
    private final String command;

    @JsonCreator
    public VerifyStartedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("at") Instant at,
            @JsonProperty("command") String command) {
        this.planId = planId;
        this.taskId = taskId;
        this.at = at == null ? Instant.now() : at;
        this.command = command;
    }

    @Override
    public String getType() {
        return "verify.started";
    }
}
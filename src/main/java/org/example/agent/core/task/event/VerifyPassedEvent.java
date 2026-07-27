package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public final class VerifyPassedEvent implements TaskEvent {
    private final String planId;
    private final String taskId;
    private final Instant at;
    private final int exitCode;
    private final String logPath;

    @JsonCreator
    public VerifyPassedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("at") Instant at,
            @JsonProperty("exitCode") int exitCode,
            @JsonProperty("logPath") String logPath) {
        this.planId = planId;
        this.taskId = taskId;
        this.at = at == null ? Instant.now() : at;
        this.exitCode = exitCode;
        this.logPath = logPath;
    }

    @Override
    public String getType() {
        return "verify.passed";
    }
}
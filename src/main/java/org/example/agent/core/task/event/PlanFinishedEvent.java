package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public final class PlanFinishedEvent implements TaskEvent {
    private final String planId;
    private final Instant at;
    private final int totalSubtasks;
    private final int totalSteps;
    private final String outcome;

    @JsonCreator
    public PlanFinishedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("at") Instant at,
            @JsonProperty("totalSubtasks") int totalSubtasks,
            @JsonProperty("totalSteps") int totalSteps,
            @JsonProperty("outcome") String outcome) {
        this.planId = planId;
        this.at = at == null ? Instant.now() : at;
        this.totalSubtasks = totalSubtasks;
        this.totalSteps = totalSteps;
        this.outcome = outcome;
    }

    @Override
    public String getType() {
        return "plan.finished";
    }
}
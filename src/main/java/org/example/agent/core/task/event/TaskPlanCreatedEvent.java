package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

@Getter
public final class TaskPlanCreatedEvent implements TaskEvent {
    private final String planId;
    private final Instant at;
    private final String goal;
    private final int subtaskCount;

    @JsonCreator
    public TaskPlanCreatedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("at") Instant at,
            @JsonProperty("goal") String goal,
            @JsonProperty("subtaskCount") int subtaskCount) {
        this.planId = planId;
        this.at = at == null ? Instant.now() : at;
        this.goal = goal;
        this.subtaskCount = subtaskCount;
    }

    @Override
    public String getType() {
        return "task_plan.created";
    }
}
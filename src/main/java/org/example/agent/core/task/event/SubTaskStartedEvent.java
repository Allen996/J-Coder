package org.example.agent.core.task.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public final class SubTaskStartedEvent implements TaskEvent {
    private final String planId;
    private final String taskId;
    private final Instant at;
    private final List<String> dependsOn;
    private final String title;

    @JsonCreator
    public SubTaskStartedEvent(
            @JsonProperty("planId") String planId,
            @JsonProperty("taskId") String taskId,
            @JsonProperty("at") Instant at,
            @JsonProperty("dependsOn") List<String> dependsOn,
            @JsonProperty("title") String title) {
        this.planId = planId;
        this.taskId = taskId;
        this.at = at == null ? Instant.now() : at;
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        this.title = title;
    }

    @Override
    public String getType() {
        return "subtask.started";
    }
}
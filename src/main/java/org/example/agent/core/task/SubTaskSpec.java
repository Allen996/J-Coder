package org.example.agent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

/**
 * LLM 在 create_plan 调用的参数 JSON 中描述子任务的最小契约。
 *
 * <p>不直接落盘 —— {@link TaskPlanRepository} 在创建时补全 taskId / createdAt / status 等字段。
 */
@Getter
@Builder
@ToString(of = {"title", "type", "dependsOn"})
public final class SubTaskSpec {

    @JsonProperty("title")
    private final String title;

    @JsonProperty("description")
    private final String description;

    @JsonProperty("type")
    private final SubTaskType type;

    @JsonProperty("dependsOn")
    private final List<String> dependsOn;

    @JsonProperty("maxSteps")
    private final Integer maxSteps;

    @JsonCreator
    public SubTaskSpec(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("type") SubTaskType type,
            @JsonProperty("dependsOn") List<String> dependsOn,
            @JsonProperty("maxSteps") Integer maxSteps) {
        this.title = title;
        this.description = description;
        this.type = type == null ? SubTaskType.IMPLEMENT : type;
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        this.maxSteps = maxSteps;
    }
}

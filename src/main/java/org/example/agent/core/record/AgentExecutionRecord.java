package org.example.agent.core.record;

import lombok.Getter;
import lombok.ToString;
import org.example.agent.core.event.AgentEvent;
import org.example.agent.core.task.AgentTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次 Agent 执行的完整快照。
 *
 * 设计目标：
 * 1) 审计：把 thought / action / observation / finish 全部存档
 * 2) 重放：ReplayService 读取本结构跑对比
 * 3) 恢复：长任务中断后用同一 executionId 拉起重跑
 *
 * 该结构由 AgentRuntimeImpl 在执行过程中增量写入，每一步 thought/action/observation
 * 都 append 一条 StepRecord 到 steps。执行结束时连同元数据一起冻结。
 *
 * 注：4.1 阶段不通过 Lombok @Builder 生成（避免与 @Singular 一起偶发的注解处理链问题），
 * 内联一个简化的静态构造器。
 */
@Getter
@ToString
public class AgentExecutionRecord {

    /** 与 AgentRuntime 内部 ConcurrentHashMap 关联的 executionId。 */
    private final String executionId;

    /** 触发本次执行的任务（用于断点重放时还原上下文）。 */
    private final AgentTask task;

    /** 执行开始时间。 */
    private final Instant startedAt;

    /** 执行结束时间。freeze() 时填充。 */
    private final Instant finishedAt;

    /** 每一步的快照，按时间顺序。 */
    private final List<StepRecord> steps;

    /** 全量 AgentEvent 流（含 FINISH / ERROR）。 */
    private final List<AgentEvent> events;

    private AgentExecutionRecord(Builder b) {
        this.executionId = b.executionId;
        this.task = b.task;
        this.startedAt = b.startedAt;
        this.finishedAt = b.finishedAt;
        this.steps = b.steps == null ? Collections.emptyList() : List.copyOf(b.steps);
        this.events = b.events == null ? Collections.emptyList() : List.copyOf(b.events);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 冻结执行快照：在 execution 结束时把 finishedAt 写入，返回一个新的不可变 record。
     * 调用方应当用返回值替换正在使用的可变 record。
     */
    public AgentExecutionRecord freeze(Instant finishedAt) {
        Builder b = builder()
                .executionId(executionId)
                .task(task)
                .startedAt(startedAt)
                .finishedAt(finishedAt);
        if (steps != null) b.steps(steps);
        if (events != null) b.events(events);
        return b.build();
    }

    public static final class Builder {
        private String executionId;
        private AgentTask task;
        private Instant startedAt;
        private Instant finishedAt;
        private List<StepRecord> steps;
        private List<AgentEvent> events;

        public Builder executionId(String v) { this.executionId = v; return this; }
        public Builder task(AgentTask v) { this.task = v; return this; }
        public Builder startedAt(Instant v) { this.startedAt = v; return this; }
        public Builder finishedAt(Instant v) { this.finishedAt = v; return this; }
        public Builder steps(List<StepRecord> v) {
            this.steps = v == null ? null : new ArrayList<>(v);
            return this;
        }
        public Builder step(StepRecord v) {
            if (this.steps == null) this.steps = new ArrayList<>();
            this.steps.add(v);
            return this;
        }
        public Builder events(List<AgentEvent> v) {
            this.events = v == null ? null : new ArrayList<>(v);
            return this;
        }
        public Builder event(AgentEvent v) {
            if (this.events == null) this.events = new ArrayList<>();
            this.events.add(v);
            return this;
        }
        public AgentExecutionRecord build() {
            return new AgentExecutionRecord(this);
        }
    }
}

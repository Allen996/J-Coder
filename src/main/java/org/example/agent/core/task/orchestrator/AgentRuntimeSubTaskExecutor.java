package org.example.agent.core.task.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.budget.ContextAwareAgentBudgetFactory;
import org.example.agent.core.budget.AgentBudget;
import org.example.agent.core.observer.ReActLoopObserver;
import org.example.agent.core.runtime.AgentRuntime;
import org.example.agent.core.task.AgentTask;
import org.example.agent.core.task.Checkpoint;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.SubTaskType;
import org.example.agent.core.task.TaskPlan;
import org.example.agent.core.task.tool.TaskLoopObserverHub;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SubTaskExecutor 的默认实现 —— 包装 {@link AgentRuntime#execute}。
 *
 * <p>每个 SubTask 启动时构造一个全新的 AgentTask，role=subtask。
 * includeHistory=false 以避免上一个 SubTask 的临时观察污染当前 SubTask 的 ephemeral（part5 §8.4）。
 */
@Slf4j
@Component
public class AgentRuntimeSubTaskExecutor implements SubTaskExecutor {

    private final AgentRuntime runtime;
    private final ContextAwareAgentBudgetFactory budgetFactory;
    private final TaskLoopObserverHub observerHub;
    private final TaskOrchestratorConfig config;

    public AgentRuntimeSubTaskExecutor(AgentRuntime runtime,
                                       ContextAwareAgentBudgetFactory budgetFactory,
                                       TaskLoopObserverHub observerHub,
                                       TaskOrchestratorConfig config) {
        this.runtime = runtime;
        this.budgetFactory = budgetFactory;
        this.observerHub = observerHub;
        this.config = config == null ? TaskOrchestratorConfig.builder().build() : config;
    }

    @Override
    public SubTaskOutcome execute(TaskPlan plan, SubTask sub, TaskLoopObserver observer) {
        AgentBudget budget = resolveBudget(plan, sub);
        AgentTask task = AgentTask.builder()
                .sessionId(plan.getSessionId())
                .input(buildSubTaskInput(plan, sub))
                .role("subtask")
                .promptId("task.subtask-executor")
                .promptVariables(buildPromptVariables(plan, sub))
                .budget(budget)
                .includeHistory(false)
                .build();

        try {
            runtime.registerObserver(observer);
            observerHub.bind(observer);
            runtime.execute(task);
        } catch (RuntimeException ex) {
            log.error("SubTask {} execution threw: {}", sub.getTaskId(), ex.getMessage(), ex);
            return SubTaskOutcome.builder()
                    .kind(SubTaskOutcome.Kind.ERROR)
                    .failureReason("runtime exception: " + ex.getMessage())
                    .stepsTaken(observer.getStepsTaken().get())
                    .build();
        } finally {
            observerHub.clear();
            try {
                runtime.registeredObservers().remove(observer);
            } catch (RuntimeException ex) {
                // best-effort
            }
        }
        return observer.buildOutcome();
    }

    private AgentBudget resolveBudget(TaskPlan plan, SubTask sub) {
        if (budgetFactory == null) {
            return AgentBudget.defaultChat();
        }
        AgentBudget base = budgetFactory.defaultBudget();
        Integer maxSteps = sub.getMaxSteps();
        if (maxSteps == null) {
            maxSteps = defaultMaxSteps(sub.getType());
        }
        // part5 §8.9 恢复机制: 重试时给一次性放宽预算(1.5× step 上限)。
        // attempts=1 表示首次执行,不放宽;attempts>1 表示已经重试过若干次,按 multiplier 放大。
        // 注意:设计要求"连续 2 次仍耗尽则该 SubTask 落 FAILED 终态,不再无限放宽"——
        // 本实现仅做一次放宽(attempts==2 时放大),attempts>2 不再继续乘,等价于"一次性"。
        if (sub.getAttempts() >= 1 && config.isRetryBudgetRelaxed()) {
            int attempts = sub.getAttempts();
            if (attempts == 1) {
                maxSteps = (int) Math.ceil(maxSteps * config.getBudgetRetriedMultiplier());
            }
            // attempts>=2 时维持已经放大过的预算,不再继续放大
        }
        return AgentBudget.builder()
                .maxTotalTokens(base.getMaxTotalTokens())
                .maxSteps(maxSteps)
                .maxWallClock(base.getMaxWallClock())
                .modelCallTimeout(base.getModelCallTimeout())
                .toolCallTimeout(base.getToolCallTimeout())
                .contextWindowMax(base.getContextWindowMax())
                .memoryTokenReservation(base.getMemoryTokenReservation())
                .maxSingleCallCompletion(base.getMaxSingleCallCompletion())
                .build();
    }

    private int defaultMaxSteps(SubTaskType type) {
        return switch (type) {
            case ANALYZE -> 8;
            case VERIFY -> 6;
            case FIX, IMPLEMENT -> 24;
            case REFACTOR -> 20;
        };
    }

    private Map<String, Object> buildPromptVariables(TaskPlan plan, SubTask sub) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("planId", plan.getPlanId());
        vars.put("planGoal", plan.getGoal());
        vars.put("subTaskId", sub.getTaskId());
        vars.put("subTaskTitle", sub.getTitle());
        vars.put("subTaskDescription", sub.getDescription() == null ? "" : sub.getDescription());
        vars.put("subTaskType", sub.getType().name());
        vars.put("dependsOn", sub.getDependsOn());
        vars.put("currentAction", sub.getCurrentAction() == null ? "" : sub.getCurrentAction());
        vars.put("nextStep", sub.getNextStep() == null ? "" : sub.getNextStep());
        vars.put("done", sub.getDone() == null ? "" : sub.getDone());
        vars.put("artifacts", sub.getArtifacts() == null ? List.of() : sub.getArtifacts());
        return vars;
    }

    private String buildSubTaskInput(TaskPlan plan, SubTask sub) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Plan ").append(plan.getPlanId()).append("] ").append(plan.getGoal()).append("\n\n");
        sb.append("[SubTask ").append(sub.getTaskId()).append(" · ")
                .append(sub.getType().name()).append("] ").append(sub.getTitle()).append("\n");
        if (sub.getDescription() != null && !sub.getDescription().isBlank()) {
            sb.append(sub.getDescription()).append("\n");
        }
        if (sub.getDone() != null && !sub.getDone().isBlank()) {
            sb.append("\n-- already done --\n").append(sub.getDone()).append("\n");
        }
        if (sub.getCurrentAction() != null && !sub.getCurrentAction().isBlank()) {
            sb.append("\n-- in progress --\n").append(sub.getCurrentAction()).append("\n");
        }
        if (sub.getNextStep() != null && !sub.getNextStep().isBlank()) {
            sb.append("\n-- next step --\n").append(sub.getNextStep()).append("\n");
        }
        if (sub.getArtifacts() != null && !sub.getArtifacts().isEmpty()) {
            sb.append("\n-- artifacts so far --\n");
            for (String a : sub.getArtifacts()) sb.append("- ").append(a).append('\n');
        }
        // part5 §8.9 重试上下文: 上次失败原因 + 最后一条 checkpoint(part5 §8.6)
        if (sub.getAttempts() > 1) {
            sb.append("\n-- attempts --\n").append(sub.getAttempts()).append("\n");
        }
        if (sub.getFailureReason() != null && !sub.getFailureReason().isBlank()) {
            sb.append("\n-- previous failure --\n").append(sub.getFailureReason()).append("\n");
        }
        Checkpoint last = sub.lastCheckpoint();
        if (last != null) {
            sb.append("\n-- last checkpoint (").append(last.getCheckpointId())
                    .append(last.isAutomatic() ? ", auto" : ", manual").append(") --\n");
            if (last.getNote() != null && !last.getNote().isBlank()) {
                sb.append("note: ").append(last.getNote()).append("\n");
            }
            if (!last.getFiles().isEmpty()) {
                sb.append("files: ").append(String.join(", ", last.getFiles())).append("\n");
            }
            if (!last.getFunctions().isEmpty()) {
                sb.append("fns:   ").append(String.join(", ", last.getFunctions())).append("\n");
            }
        }
        sb.append("\n完成本任务后调用 complete_subtask；放弃则调用 fail_subtask；中途可调用 save_checkpoint 打点。\n");
        return sb.toString();
    }
}
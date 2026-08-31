package org.example.agent.core.task.subagent.tool;

import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.CheckpointService;
import org.example.agent.core.task.dag.DagGraph;
import org.example.agent.core.task.dag.DagState;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.example.agent.tool.spi.ToolDescriptorRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code checkpoint} 工具（阶段 4 接通 CheckpointService）。
 *
 * <p>主 Agent 专属。触发 Checkpoint 写入（完整状态快照 + git commit hash）。
 * 触发时机：
 * <ul>
 *   <li>主 Agent 主动调（识别到两难决策点）</li>
 *   <li>用户手动 {@code /checkpoint}</li>
 * </ul>
 *
 * <p>写入路径：{@code .agent/sessions/{sessionId}/checkpoints/{seq}-{reason}/manifest.json}。
 * 返回 checkpointId —— 后续主 Agent 可用此 id 关联决策。
 *
 * <p>要求：
 * <ul>
 *   <li>git 必须可用（{@code git rev-parse HEAD}）—— 不可用时报错</li>
 *   <li>当前 session 必须有 active plan（task-detail.md 阶段 4 决策）</li>
 * </ul>
 */
@Slf4j
@Component
public class CheckpointTool {

    private final CheckpointService checkpointService;
    private final TaskOrchestrator orchestrator;
    private final ToolDescriptorRegistry toolDescriptorRegistry;

    @Autowired
    public CheckpointTool(
            CheckpointService checkpointService,
            @Lazy TaskOrchestrator orchestrator,
            ToolDescriptorRegistry toolDescriptorRegistry) {
        this.checkpointService = checkpointService;
        this.orchestrator = orchestrator;
        this.toolDescriptorRegistry = toolDescriptorRegistry;
    }

    @Tool(description = "触发一个 Checkpoint 快照,等待用户介入决策。"
            + "reason: 触发原因短述(同时作为目录 slug)。"
            + "decisionQuestion: 需要用户决定的具体问题。"
            + "阶段 4 接通: 调 git rev-parse HEAD + 收集 system prompt/工具/DAG/记忆 状态,"
            + "原子写 manifest.json。返回 checkpointId。")
    public String checkpoint(
            @ToolParam(description = "触发原因(短,例如 'choose-cache-strategy')") String reason,
            @ToolParam(description = "需要用户决策的具体问题") String decisionQuestion,
            @ToolParam(description = "与决策相关的文件路径(可选,逗号分隔)", required = false) String relevantArtifacts) {
        if (reason == null || reason.isBlank()) {
            return "[error] reason is required";
        }
        Optional<DagGraph> activeGraphOpt = orchestrator.activeGraph();
        if (activeGraphOpt.isEmpty()) {
            return "[error] no active plan —— checkpoint requires an active TaskPlan";
        }
        DagGraph graph = activeGraphOpt.get();
        String sessionId = graph.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "[error] active plan has no sessionId";
        }
        // DagState 从 dag-state.json 读 —— 实时序列化状态
        DagState state = loadDagState(sessionId);

        List<String> artifacts = parseArtifacts(relevantArtifacts);

        try {
            CheckpointService.Result r = checkpointService.createPending(
                    sessionId,
                    reason,
                    decisionQuestion == null ? "" : decisionQuestion,
                    artifacts,
                    renderSystemPrompt(graph),
                    toolRegistrySnapshot(),
                    graph,
                    state,
                    orchestrator.getCurrentCheckpointId());
            return "[checkpoint created] id=" + r.checkpoint().getCheckpointId()
                    + " reason='" + r.checkpoint().getReason() + "'"
                    + " status=" + r.checkpoint().getStatus()
                    + " manifest=" + projectRelative(r.manifestPath())
                    + " awaiting user decision";
        } catch (CheckpointService.GitUnavailableException ex) {
            log.warn("checkpoint failed: git unavailable: {}", ex.getMessage());
            return "[error] checkpoint requires git but it is unavailable: " + ex.getMessage();
        } catch (RuntimeException ex) {
            log.warn("checkpoint failed: {}", ex.getMessage(), ex);
            return "[error] checkpoint failed: " + ex.getMessage();
        }
    }

    private static List<String> parseArtifacts(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String projectRelative(Path p) {
        if (p == null) return "?";
        // 把绝对路径切成项目相对 —— 取末尾 5 段
        List<String> parts = new ArrayList<>();
        for (Path cur = p; cur != null && parts.size() < 5; cur = cur.getParent()) {
            parts.add(0, cur.getFileName().toString());
        }
        if (parts.size() >= 5) parts.add(0, "...");
        return String.join(java.io.File.separator, parts);
    }

    private String renderSystemPrompt(DagGraph graph) {
        // 阶段 4 简化：把 graph 元数据 + 当前角色渲染成简短 system prompt 摘要。
        // 真正的 system prompt 装配由 SpringAiReactAgentProvider.build() 完成,
        // 主 Agent 在 checkpoint 工具调用时不一定有它 —— 这里给一个可读的占位。
        StringBuilder sb = new StringBuilder();
        sb.append("Plan goal: ").append(graph.getGoal()).append("\n");
        sb.append("Plan id: ").append(graph.getPlanId()).append("\n");
        sb.append("Session: ").append(graph.getSessionId()).append("\n");
        sb.append("Total nodes: ").append(graph.size()).append("\n");
        return sb.toString();
    }

    private List<String> toolRegistrySnapshot() {
        if (toolDescriptorRegistry == null) return List.of();
        return toolDescriptorRegistry.all().stream()
                .map(d -> d.name() + (d.mainAgentOnly() ? "(main-only)" : ""))
                .sorted()
                .collect(Collectors.toList());
    }

    private DagState loadDagState(String sessionId) {
        // 优先从 orchestrator 拿 in-memory 状态（最新），否则从磁盘读
        Optional<DagState> fromOrch = orchestrator.activeState();
        if (fromOrch.isPresent()) return fromOrch.get();
        // orchestrator 未持有时 —— 读磁盘
        Path p = orchestrator.getDagStateRepository().dagStatePath(sessionId);
        if (!Files.exists(p)) return null;
        try {
            return orchestrator.getDagStateRepository().mapper().readValue(
                    Files.readAllBytes(p),
                    org.example.agent.core.task.dag.DagState.class);
        } catch (Exception ex) {
            log.warn("loadDagState failed for {}: {}", p, ex.getMessage());
            return null;
        }
    }
}

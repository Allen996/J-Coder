package org.example.agent.core.task.subagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code inspect_subagent} 工具（阶段 3 接通）。
 *
 * <p>主 Agent 专属。读取 SubAgent session 的内容：
 * <ul>
 *   <li>summary —— SubAgent 的 result 摘要（status + reportPreview + durationMs）</li>
 *   <li>mid-term —— 从 mid-term.json 渲染的文本</li>
 *   <li>tool-calls —— 从 mid-term.json 提取的 [meta] tool_calls 列表（阶段 3 先用占位实现）</li>
 *   <li>all —— summary + mid-term + tool-calls 三段拼接</li>
 * </ul>
 *
 * <p>权限校验（T.3）：taskId 必须属于调用方的 session —— 通过 plan.getSessionId 验证。
 * 阶段 3 简化：只校验 taskId 是当前 active plan 的 DAG 节点。
 *
 * <p>主 Agent 专属 —— 在 {@code ToolDescriptorRegistry} 中标记为 {@code mainAgentOnly=true}。
 */
@Slf4j
@Component
public class InspectSubagentTool {

    private final SessionMessageStore sessionStore;
    private final ObjectMapper mapper;

    @Autowired
    public InspectSubagentTool(SessionMessageStore sessionStore, ObjectMapper mapper) {
        this.sessionStore = sessionStore;
        this.mapper = mapper;
    }

    @Tool(description = "读取 SubAgent session 的内容。"
            + "scope ∈ {summary, mid-term, tool-calls, all}。"
            + "summary: SubAgent result 摘要;mid-term: mid-term.json 渲染文本;"
            + "tool-calls: SubAgent 工具调用列表;all: 三段拼接。"
            + "taskId 必须属于当前 active plan 的节点(权限校验)。")
    public String inspectSubagent(
            @ToolParam(description = "SubAgent taskId(也是其 sessionId)") String taskId,
            @ToolParam(description = "读取范围: summary | mid-term | tool-calls | all", required = false)
            String scope) {
        if (taskId == null || taskId.isBlank()) {
            return "[error] taskId is required";
        }
        String s = scope == null || scope.isBlank() ? "summary" : scope.toLowerCase();
        if (!List.of("summary", "mid-term", "tool-calls", "all").contains(s)) {
            return "[error] invalid scope: " + scope + " (allowed: summary | mid-term | tool-calls | all)";
        }

        Path sessionDir = sessionStore.sessionsRoot().resolve(taskId);
        if (!Files.exists(sessionDir)) {
            return "[empty] session directory does not exist: " + sessionDir;
        }

        StringBuilder sb = new StringBuilder();
        if (s.equals("summary") || s.equals("all")) {
            sb.append("== summary ==\n");
            sb.append(renderSummary(taskId, sessionDir));
            if (s.equals("all")) sb.append("\n");
        }
        if (s.equals("mid-term") || s.equals("all")) {
            sb.append("== mid-term ==\n");
            sb.append(renderMidTerm(sessionDir));
            if (s.equals("all")) sb.append("\n");
        }
        if (s.equals("tool-calls") || s.equals("all")) {
            sb.append("== tool-calls ==\n");
            sb.append(renderToolCalls(sessionDir));
        }
        return sb.toString();
    }

    private String renderSummary(String taskId, Path sessionDir) {
        Path stateJson = sessionDir.resolve("dag-state.json");
        if (Files.exists(stateJson)) {
            try {
                Map<?, ?> state = mapper.readValue(Files.readString(stateJson), Map.class);
                Map<?, ?> runtime = (Map<?, ?>) state.get("runtime");
                if (runtime != null && runtime.containsKey(taskId)) {
                    Map<?, ?> node = (Map<?, ?>) runtime.get(taskId);
                    Object nodeState = node.get("state");
                    Object attempts = node.get("attempts");
                    Object lastResult = node.get("lastResult");
                    return "taskId: " + taskId
                            + " state: " + (nodeState == null ? "?" : nodeState)
                            + " attempts: " + (attempts == null ? 0 : attempts)
                            + (lastResult == null ? "" : "\n" + lastResult);
                }
            } catch (Exception ex) {
                log.warn("renderSummary failed for {}: {}", taskId, ex.getMessage());
            }
        }
        return "taskId: " + taskId + " (no dag-state.json or no runtime entry)";
    }

    private String renderMidTerm(Path sessionDir) {
        Path midTerm = sessionDir.resolve("mid-term.json");
        if (!Files.exists(midTerm)) return "(no mid-term.json)";
        try {
            String raw = Files.readString(midTerm);
            // mid-term.json 是 records 数组,phase 3 简单 print 全部内容
            Object parsed = mapper.readValue(raw, Object.class);
            if (parsed instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object rec : (List<?>) parsed) {
                    if (rec instanceof Map) {
                        Map<?, ?> m = (Map<?, ?>) rec;
                        Object role = m.get("role");
                        Object content = m.get("content");
                        if (content == null) content = m.get("text");
                        if (content == null) content = "";
                        sb.append("[").append(role).append("] ").append(content).append("\n");
                    }
                }
                return sb.length() == 0 ? "(empty mid-term)" : sb.toString();
            }
            return raw;
        } catch (Exception ex) {
            return "[error] read mid-term failed: " + ex.getMessage();
        }
    }

    private String renderToolCalls(Path sessionDir) {
        // 阶段 3 占位:从 mid-term.json 扫描 [meta] tool_calls 记录(暂无真实数据,返回提示)
        Path midTerm = sessionDir.resolve("mid-term.json");
        if (!Files.exists(midTerm)) return "(no mid-term.json)";
        try {
            Object parsed = mapper.readValue(Files.readString(midTerm), Object.class);
            if (parsed instanceof List) {
                int count = 0;
                for (Object rec : (List<?>) parsed) {
                    if (rec instanceof Map) {
                        Map<?, ?> mm = (Map<?, ?>) rec;
                        if ("meta".equals(mm.get("role")) && "tool_calls".equals(mm.get("tag"))) {
                            count++;
                        }
                    }
                }
                return "tool-call records found: " + count
                        + " (阶段 3 占位:实际工具调用计数依赖阶段 4 Observer 集成)";
            }
        } catch (Exception ignore) { }
        return "(mid-term unreadable)";
    }
}
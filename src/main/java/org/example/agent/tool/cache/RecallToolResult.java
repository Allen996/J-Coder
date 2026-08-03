package org.example.agent.tool.cache;

import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 召回外置工具结果的独立工具。
 *
 * <p>LLM 看到结果末尾的 {@code [stored as #xxxxxxxx]} 后,可以调本工具精确取回。
 * 三个过滤器互斥:
 * <ul>
 *   <li>不传 → 全量召回</li>
 *   <li>startLine + endLine → 行范围</li>
 *   <li>pattern → 正则,匹配行 ±2 行 context,最多 100 行,不带行号</li>
 * </ul>
 *
 * <p>id 必须匹配 {@code ^[a-z0-9]{8}$}。非法 id 直接抛 INVALID_ARGUMENT,不查存储(防路径穿越)。
 */
@Component
public class RecallToolResult {

    private static final java.util.regex.Pattern VALID_ID = java.util.regex.Pattern.compile("^[a-z0-9]{8}$");

    private final ToolResultStore store;

    public RecallToolResult(ToolResultStore store) {
        this.store = store;
    }

    @Tool(description = "召回外置的工具结果。"
            + "默认全量;可用 startLine/endLine 取行范围,或 pattern 正则过滤(匹配行 ±2 行 context,最多 100 行,不带行号)。"
            + "startLine/endLine 与 pattern 互斥。")
    public String recallToolResult(
            @ToolParam(description = "8 位 [a-z0-9] id,来自工具结果末尾的 [stored as #<id>]") String id,
            @ToolParam(description = "起始行 1-based", required = false) Integer startLine,
            @ToolParam(description = "结束行 1-based", required = false) Integer endLine,
            @ToolParam(description = "正则,匹配行 ±2 行 context,最多 100 行,不带行号", required = false) String pattern) {

        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new ToolExecutionException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "id 格式非法: " + id,
                    "id 必须是 8 位 [a-z0-9],来自 [stored as #xxxxxxxx] 提示",
                    FailureKind.PARAM);
        }
        if ((startLine != null || endLine != null) && (pattern != null && !pattern.isEmpty())) {
            throw new ToolExecutionException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "startLine/endLine 与 pattern 互斥",
                    "二选一,或不传取全量",
                    FailureKind.PARAM);
        }

        ToolResultStore.RecallResult result = store.recall(id, startLine, endLine,
                (pattern != null && !pattern.isEmpty()) ? pattern : null);
        if (result instanceof ToolResultStore.RecallResult.Ok ok) {
            return ok.content();
        }
        if (result instanceof ToolResultStore.RecallResult.Expired e) {
            return "[EXPIRED] #" + e.id() + " 已过期,记录已删除。请重新调用原工具。";
        }
        if (result instanceof ToolResultStore.RecallResult.StaleRemoved s) {
            return "[STALE_AND_REMOVED] #" + s.id() + " 依赖的文件已变更,记录已删除。请重新调用原工具。";
        }
        if (result instanceof ToolResultStore.RecallResult.NotFound n) {
            return "[NOT_FOUND] #" + n.id() + " 不存在或已被清理。";
        }
        if (result instanceof ToolResultStore.RecallResult.InvalidId i) {
            return "[INVALID_ID] #" + i.id() + " 格式非法。";
        }
        return "[UNKNOWN] recall 返回未知类型";
    }
}

package org.example.agent.tool.cache;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 单条外置工具结果的持久化形态。
 *
 * <p>字段对应 {@code .agent-cache/tool-results/tr-<id>.json} 里的 JSON 结构。
 * JSON 字段名固定,Jackson 序列化时按字段名输出。
 *
 * @param id              8 位 [a-z0-9] 短 id,作为占位符与文件名一部分
 * @param toolName        来源工具名
 * @param toolArgs        来源工具调用参数(用于 recall 时 hint / 调试)
 * @param result          工具返回的完整字符串
 * @param capturedAt      写入时间(UTC)
 * @param expiresAt       过期时间(UTC);recall 时若超过则删除
 * @param resultSizeBytes 结果字节数(便于 LLM 看元数据)
 * @param watchedPaths    该记录依赖的本地路径(用于 stale 检查);空表示无依赖
 * @param watchedHashes   写入时 watchedPaths 的当前 mtime 列表(逐项对应);recall 时重新比对
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResultRecord(
        String id,
        String toolName,
        Map<String, Object> toolArgs,
        String result,
        Instant capturedAt,
        Instant expiresAt,
        long resultSizeBytes,
        List<String> watchedPaths,
        List<String> watchedHashes
) {
}

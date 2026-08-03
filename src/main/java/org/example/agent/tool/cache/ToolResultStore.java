package org.example.agent.tool.cache;

import org.example.agent.tool.spi.ToolDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 工具结果外置存储 + 召回接口。
 *
 * <p>写时机:ToolGateway 在工具调用成功且 descriptor.cacheable()=true 时调 save。
 * <p>召回:RecallToolResult 工具或 ReActLoop auto-inline 时调 recall。
 * <p>失效:FileTools.writeFile/editFile 调 invalidateByPath,让同一文件的所有缓存立即作废。
 */
public interface ToolResultStore {

    /**
     * 持久化一条工具结果,返回分配的 id。
     *
     * @param toolName     工具名
     * @param args         工具调用参数
     * @param result       工具返回字符串
     * @param executionId  当前 executionId(用于调试/审计,不在主路径使用)
     * @param descriptor   工具描述符,可能影响 watchedPaths 推断
     * @return 分配的 8 位 id
     */
    String save(String toolName,
                Map<String, Object> args,
                String result,
                String executionId,
                ToolDescriptor descriptor);

    /**
     * 召回结果。带 stale/expired 检查与可选过滤。
     *
     * @param id         8 位 id
     * @param startLine  1-based 起始行(含),null 表示从头
     * @param endLine    1-based 结束行(含),null 表示到末尾
     * @param pattern    正则;每条匹配行 ±2 行 context,最多 100 行
     * @return 结构化结果(成功/过期/stale/未找到)
     */
    RecallResult recall(String id, Integer startLine, Integer endLine, String pattern);

    /**
     * 命中某本地路径变更时,失效所有 watchedPaths 包含该路径的缓存。
     * writeFile/editFile 在动盘之后调。
     */
    void invalidateByPath(Path path);

    /**
     * 文本里出现的所有 {@code #<id>} 占位符的 id 列表(去重,顺序保持)。
     */
    List<String> scanIds(String text);

    /**
     * 给 LLM 看的元数据提示(用于 auto-inline 超预算场景)。
     * 一行字符串,描述 id 对应的工具/参数/大小,不包含 result 内容。
     */
    String metadataHint(String id);

    /**
     * 后台清理入口:总量超过 maxTotalBytes 时按 capturedAt LRU 删。
     * 由 ScheduledExecutorService 周期调,也可手动触发。
     */
    void evictIfOverBudget();

    /** 召回结果封装。 */
    sealed interface RecallResult {
        /** 命中且新鲜(过滤后)内容。 */
        record Ok(String content) implements RecallResult {}
        /** 命中但已过期,记录已删除。 */
        record Expired(String id) implements RecallResult {}
        /** 命中但依赖文件已变更,记录已删除。 */
        record StaleRemoved(String id) implements RecallResult {}
        /** 未找到(可能 id 错,或已被清理)。 */
        record NotFound(String id) implements RecallResult {}
        /** id 格式非法。 */
        record InvalidId(String id) implements RecallResult {}
    }
}

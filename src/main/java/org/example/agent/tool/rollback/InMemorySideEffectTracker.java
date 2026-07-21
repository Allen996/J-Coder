package org.example.agent.tool.rollback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1 默认实现:内存里按 executionId 分桶,deque 模拟栈。
 *
 * <p>线程模型:
 * <ul>
 *   <li>{@link #bind} 由 {@code ReActLoop.subscribe()} 入口同步调用,设置 ThreadLocal</li>
 *   <li>{@link #recordFileChange} 由 {@code @Tool} 方法在 ThreadLocal 命中当前 executionId 时入栈</li>
 *   <li>{@link #rollbackAll} 由 {@code ToolGateway.handleFailure} 同步调用</li>
 *   <li>{@link #clear} 由 {@code ReActLoop.subscribe()} 出口(finally)清理</li>
 * </ul>
 *
 * <p>线程安全:桶本身并发安全;同一 executionId 内 push/poll 必须落在同一线程(由 bind/unbind 的
 * ThreadLocal 语义保证)。{@code runtime.stream()} 默认在 boundedElastic 上执行,Spring AI 工具调用
 * 也回落到同一线程,因此 ThreadLocal 与并发 HashMap 的组合在 v1 是安全的。
 */
@Component
public class InMemorySideEffectTracker implements SideEffectTracker {

    private static final Logger log = LoggerFactory.getLogger(InMemorySideEffectTracker.class);

    private static final ThreadLocal<String> CURRENT_EXECUTION = new ThreadLocal<>();

    private final Map<String, Deque<SideEffectRecord>> sessions = new ConcurrentHashMap<>();

    @Override
    public void bind(String executionId) {
        if (executionId == null) throw new IllegalArgumentException("executionId must not be null");
        CURRENT_EXECUTION.set(executionId);
        sessions.computeIfAbsent(executionId, k -> new ArrayDeque<>());
        log.debug("SideEffectTracker bound executionId={}", executionId);
    }

    @Override
    public void clear() {
        String exec = CURRENT_EXECUTION.get();
        if (exec != null) {
            sessions.remove(exec);
            CURRENT_EXECUTION.remove();
            log.debug("SideEffectTracker cleared executionId={}", exec);
        }
    }

    @Override
    public void recordFileChange(String toolName, String path, byte[] preState) {
        String exec = CURRENT_EXECUTION.get();
        if (exec == null) {
            // 调用方未 bind —— 多半是测试或非 task 路径。不报错,只丢日志,不污染栈。
            log.debug("recordFileChange called without bind: tool={} path={}", toolName, path);
            return;
        }
        Deque<SideEffectRecord> stack = sessions.get(exec);
        if (stack == null) {
            log.warn("executionId={} not bound in map, ignored record", exec);
            return;
        }
        stack.push(new SideEffectRecord(toolName, path, preState));
        log.debug("executionId={} tracked side effect: tool={} path={} preStateBytes={}",
                exec, toolName, path, preState == null ? -1 : preState.length);
    }

    @Override
    public RollbackSummary rollbackAll() {
        String exec = CURRENT_EXECUTION.get();
        if (exec == null) return new RollbackSummary(0, List.of());
        Deque<SideEffectRecord> stack = sessions.get(exec);
        if (stack == null || stack.isEmpty()) {
            return new RollbackSummary(0, List.of());
        }

        List<String> rolled = new ArrayList<>();
        int success = 0;
        SideEffectRecord rec;
        while ((rec = stack.pollFirst()) != null) {
            String desc = undoOne(rec);
            rolled.add(desc);
            if (!desc.contains("FAILED")) success++;
        }
        if (success == 0 && rolled.isEmpty()) {
            return new RollbackSummary(0, List.of());
        }
        log.info("executionId={} rollbackAll success={} total={}", exec, success, rolled.size());
        return new RollbackSummary(success, rolled);
    }

    private String undoOne(SideEffectRecord rec) {
        Path p = Paths.get(rec.path());
        try {
            if (rec.preState() == null) {
                // 文件原本不存在 —— rollback = 删除当前文件
                boolean deleted = Files.deleteIfExists(p);
                return rec.toolName() + ": " + rec.path() + (deleted ? " (deleted)" : " (no-op: already gone)");
            }
            Files.writeString(p, new String(rec.preState(), StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return rec.toolName() + ": " + rec.path() + " (restored " + rec.preState().length + " bytes)";
        } catch (IOException ex) {
            log.warn("rollback failed for {}: {}", rec.path(), ex.getMessage());
            return rec.toolName() + ": " + rec.path() + " (FAILED: " + ex.getMessage() + ")";
        }
    }
}

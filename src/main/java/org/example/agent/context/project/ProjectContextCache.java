package org.example.agent.context.project;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ProjectContext} 的应用级缓存（part3.md §6.1 Project Layer "启动 + /load 时刷新"）。
 *
 * <p>单一职责：维护一个 latest {@link ProjectContext} 引用。
 * <ul>
 *   <li>启动期 {@code @PostConstruct} 调一次 {@link ProjectScanner#scan(Path)} 完成首次扫描。</li>
 *   <li>{@link #refresh()} 由 /load 命令调用，重新扫描并替换缓存。</li>
 *   <li>{@link #current()} 由 ContextBuilder 在装配 Project Layer 时读。</li>
 * </ul>
 *
 * <p>并发安全：用 {@link AtomicReference} 持有最新快照。scan() 期间读到的仍是旧快照，
 * 这是 part3.md "Project Layer 在 step 间隙刷新" 的天然实现 —— step 间隙切到新快照，
 * 不需要锁。
 *
 * <p>降级：扫描失败时 cache 保持不变（不抛错），只是日志 warn。
 */
@Component
public class ProjectContextCache {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextCache.class);

    private final ProjectScanner scanner;
    private final Path defaultRoot;
    private final AtomicReference<ProjectContext> ref = new AtomicReference<>();

    public ProjectContextCache(ProjectScanner scanner,
                               @Value("${agent.project-root:#{T(java.nio.file.Paths).get('').toAbsolutePath().toString()}}") String root) {
        this.scanner = scanner;
        this.defaultRoot = Paths.get(root).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 重新扫描并替换缓存。 */
    public ProjectContext refresh() {
        return refresh(defaultRoot);
    }

    public ProjectContext refresh(Path root) {
        try {
            ProjectContext ctx = scanner.scan(root);
            ref.set(ctx);
            log.info("ProjectContext refreshed: root={} files={} sources={} pkg={} truncated={}",
                    ctx.getRoot(), ctx.getTotalFileCount(), ctx.getSourceFileCount(),
                    ctx.getPackageManagerOrDefault(), ctx.isTruncated());
            return ctx;
        } catch (RuntimeException ex) {
            log.warn("ProjectContext refresh failed, keeping previous snapshot: {}", ex.getMessage(), ex);
            ProjectContext prev = ref.get();
            if (prev != null) return prev;
            // 首次扫描失败 —— 返回空快照避免 NPE
            ProjectContext empty = ProjectContext.builder()
                    .root(root)
                    .fileTree(FileTreeNode.empty())
                    .sourceFileCount(0)
                    .totalFileCount(0)
                    .claudeMd("")
                    .readme("")
                    .keyConfigFiles(java.util.Collections.emptyList())
                    .packageManager("unknown")
                    .scannedAt(System.currentTimeMillis())
                    .truncated(false)
                    .build();
            ref.set(empty);
            return empty;
        }
    }

    public ProjectContext current() {
        ProjectContext c = ref.get();
        if (c != null) return c;
        // 极端兜底：@PostConstruct 没跑（比如单元测试中未走 Spring 生命周期）
        return refresh();
    }
}
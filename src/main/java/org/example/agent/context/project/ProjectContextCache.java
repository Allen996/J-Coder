package org.example.agent.context.project;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 项目上下文适配器（part3.md §6.7）。
 *
 * <p>ProjectScanner 不再作为启动期组件存在 —— 启动时不再扫描项目。
 * 运行时需要时，通过工具（{@code scan_files} / {@code read_file} / {@code search}）按需查询。
 *
 * <p>本类保留仅为兼容旧接口（{@link org.example.cli.command.impl.LoadCommand} 等）；
 * 新代码不再依赖它。处于 deprecated 状态，等待调用方迁移完成后再删除。
 */
@Component
@Deprecated
public class ProjectContextCache {

    private final AtomicReference<ProjectContext> ref = new AtomicReference<>();

    public ProjectContext current() {
        return ref.get();
    }

    /** 旧接口 —— 不再触发实际扫描。 */
    public ProjectContext refresh() {
        return ref.get();
    }

    /** 旧接口 —— 不再触发实际扫描。 */
    public ProjectContext refresh(java.nio.file.Path root) {
        return ref.get();
    }
}

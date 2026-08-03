package org.example.agent.context.project;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.nio.file.Path;
import java.util.List;

/**
 * 单次项目扫描的输出快照（part3.md §6.2）。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code root}：扫描根路径，与启动期 CLI 的 {@code agent.project-root} 一致。</li>
 *   <li>{@code fileTree}：深度 ≤ 3 的精简文件树，按目录分层（{@link FileTreeNode#children}）。</li>
 *   <li>{@code sourceFileCount} / {@code totalFileCount}：用于 /cost 与决策（&gt;1000 触发降采样）。</li>
 *   <li>{@code claudeMd} / {@code readme}：从项目根读到的项目级配置；缺失时为空字符串。</li>
 *   <li>{@code keyConfigFiles}：pom.xml / package.json / go.mod 等构建入口。key=相对路径，value=全文。</li>
 *   <li>{@code packageManager}：maven / npm / gradle / go / pip / unknown，源自 keyConfigFiles 命中。</li>
 *   <li>{@code scannedAt}：扫描时间戳，供 /load 命令判断 "项目层是否需要刷新"。</li>
 *   <li>{@code truncated}：是否降采样（&gt;1000 源码文件时取 mtime 最新前 1000）。</li>
 * </ul>
 *
 * 不可变：scan() 一次返回一个新实例，缓存 / 刷新都基于整个对象替换（CAS 风格）。
 */
@Getter
@Builder
@ToString(of = {"root", "sourceFileCount", "totalFileCount", "packageManager", "truncated", "scannedAt"})
public final class ProjectContext {

    private final Path root;
    private final FileTreeNode fileTree;
    private final int sourceFileCount;
    private final int totalFileCount;
    private final String claudeMd;
    private final String readme;
    private final List<KeyConfigFile> keyConfigFiles;
    private final String packageManager;
    private final long scannedAt;
    private final boolean truncated;

    @Getter
    @Builder
    @ToString(of = {"relativePath", "type"})
    public static final class KeyConfigFile {
        /** 相对 {@link ProjectContext#root} 的路径（POSIX 风格，统一正斜杠）。 */
        private final String relativePath;
        /** 文件类型（pom / package_json / go_mod / pyproject / gradle 等）。 */
        private final String type;
        /** 文件全文。 */
        private final String content;

        public KeyConfigFile(String relativePath, String type, String content) {
            this.relativePath = relativePath;
            this.type = type;
            this.content = content == null ? "" : content;
        }
    }

    public String getPackageManagerOrDefault() {
        return packageManager == null || packageManager.isBlank() ? "unknown" : packageManager;
    }
}
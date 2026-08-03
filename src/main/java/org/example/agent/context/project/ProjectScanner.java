package org.example.agent.context.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 项目扫描器（part3.md §6.2）。
 *
 * <p>输入：项目根路径 + 可选深度 / 上限配置（来自 {@code agent.project-root} 与 /config）。
 * 输出：{@link ProjectContext} —— 文件树 + 关键文件 + 包管理器识别。
 *
 * <p>规则清单（与 part3.md §6.2 一一对应）：
 * <ol>
 *   <li>应用 .gitignore 规则（{@link GitignoreMatcher}）。</li>
 *   <li>排除内置黑名单目录（node_modules / target / build / dist / .git / __pycache__ 等）。</li>
 *   <li>深度限制 3。</li>
 *   <li>文件数 &gt; 1000 → 仅保留源码文件，按 mtime 倒序取前 1000。</li>
 *   <li>读取 CLAUDE.md / README.md（README 截断到 200 行）。</li>
 *   <li>检测包管理器（pom.xml → maven；package.json → npm；等）。</li>
 * </ol>
 *
 * <p>并发安全：scan() 自身无状态，每次返回新对象。可以被多个调用方（CLI 启动期 + /load 刷新）复用。
 *
 * <p>异常策略：扫描失败（IO / 路径不存在）一律降级返回"空 ProjectContext"，避免阻塞 CLI 启动。
 */
@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    /** part3.md §6.2 规则 2：硬编码排除目录。 */
    public static final List<String> HARD_EXCLUDED_DIRS = List.of(
            "node_modules", "target", "build", "dist", ".git", "__pycache__",
            ".gradle", ".idea", ".vscode", ".claude", "out", "vendor"
    );

    /** part3.md §6.2 规则 6：关键配置文件识别。 */
    private static final Map<String, String> KEY_CONFIG_FILES = new LinkedHashMap<>();
    static {
        KEY_CONFIG_FILES.put("pom.xml", "pom");
        KEY_CONFIG_FILES.put("build.gradle", "gradle");
        KEY_CONFIG_FILES.put("build.gradle.kts", "gradle");
        KEY_CONFIG_FILES.put("settings.gradle", "gradle");
        KEY_CONFIG_FILES.put("package.json", "package_json");
        KEY_CONFIG_FILES.put("go.mod", "go_mod");
        KEY_CONFIG_FILES.put("Cargo.toml", "cargo");
        KEY_CONFIG_FILES.put("pyproject.toml", "pyproject");
        KEY_CONFIG_FILES.put("requirements.txt", "pip");
    }

    /** 源码后缀，用于 ">1000 文件" 降采样。 */
    private static final List<String> SOURCE_EXTENSIONS = List.of(
            ".java", ".kt", ".scala", ".groovy",
            ".js", ".ts", ".jsx", ".tsx", ".mjs", ".cjs",
            ".py", ".rb", ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp",
            ".cs", ".swift", ".m", ".mm"
    );

    /** 默认文件树深度（part3.md §6.2 规则 3）。 */
    public static final int DEFAULT_MAX_DEPTH = 3;
    /** 默认文件上限（part3.md §6.2 规则 4）。 */
    public static final int DEFAULT_MAX_FILES = 1000;
    /** README 截断行数（part3.md §6.2 规则 6）。 */
    public static final int README_MAX_LINES = 200;

    /**
     * 执行一次完整扫描。
     *
     * @param root 项目根路径；为 null / 不存在时返回空 ProjectContext
     * @return 永不为 null 的 ProjectContext
     */
    public ProjectContext scan(Path root) {
        return scan(root, DEFAULT_MAX_DEPTH, DEFAULT_MAX_FILES);
    }

    public ProjectContext scan(Path root, int maxDepth, int maxFiles) {
        if (root == null) {
            return emptyContext(null);
        }
        Path absRoot = root.toAbsolutePath().normalize();
        if (!Files.exists(absRoot) || !Files.isDirectory(absRoot)) {
            log.warn("ProjectScanner: root does not exist or is not a directory: {}", absRoot);
            return emptyContext(absRoot);
        }

        GitignoreMatcher gitignore = loadGitignore(absRoot);
        ScanCollector collector = new ScanCollector(absRoot, gitignore, maxDepth, maxFiles);

        // Files.walk(path, maxDepth) 的 maxDepth 是「目录层级数」，最深层的文件不会被产出
        // （比如 maxDepth=3 不会 yield 第四层的 src/main/java/Foo.java）。
        // 这里把 walker 的 maxDepth 设为 maxDepth + 1，让最深目录下的文件也能被产出；
        // 然后 visit() 里再用 nameCount 二次过滤。
        try (Stream<Path> stream = Files.walk(absRoot, maxDepth + 1)) {
            stream.filter(p -> !p.equals(absRoot))
                    .forEach(collector::visit);
        } catch (IOException ex) {
            log.warn("ProjectScanner: walk failed at {}: {}", absRoot, ex.getMessage(), ex);
            return emptyContext(absRoot);
        }

        collector.complete();

        String claudeMd = readText(absRoot.resolve("CLAUDE.md"), -1);
        String readme = readText(absRoot.resolve("README.md"), README_MAX_LINES);

        List<ProjectContext.KeyConfigFile> keyConfigs = new ArrayList<>();
        for (Map.Entry<String, Path> e : collector.keyConfigHits.entrySet()) {
            String content = readText(e.getValue(), -1);
            String type = KEY_CONFIG_FILES.getOrDefault(e.getKey(), "unknown");
            keyConfigs.add(new ProjectContext.KeyConfigFile(e.getKey(), type, content));
        }

        return ProjectContext.builder()
                .root(absRoot)
                .fileTree(collector.buildTree())
                .sourceFileCount(collector.sourceFiles.size())
                .totalFileCount(collector.allFiles.size())
                .claudeMd(claudeMd)
                .readme(readme)
                .keyConfigFiles(keyConfigs)
                .packageManager(detectPackageManager(collector.keyConfigHits.keySet()))
                .scannedAt(Instant.now().toEpochMilli())
                .truncated(collector.truncated)
                .build();
    }

    // ============== 内部：收集器 ==============

    /**
     * 单次 scan 的可变状态。集中放这里比传 8 个参数给工具方法清爽，且 scan() 是同步单线程访问，
     * 不需要并发保护。
     *
     * <p>树构建策略：使用 {@code TreeMap<Path, MutableNode>} 按相对路径排序记录所有被保留的目录
     * 与文件节点。finalize() 自顶向下拼出最终 FileTreeNode。简化实现：每个节点预创建，
     * 子节点在结束时按字典序 attach —— 避免在 walk 中维护复杂的栈。
     */
    private static final class ScanCollector {
        final Path root;
        final GitignoreMatcher gitignore;
        final int maxDepth;
        final int maxFiles;
        final Map<Path, MutableNode> nodes = new TreeMap<>();
        final Map<String, Path> keyConfigHits = new LinkedHashMap<>();
        final List<Path> allFiles = new ArrayList<>();
        final List<Path> sourceFiles = new ArrayList<>();
        boolean truncated = false;

        ScanCollector(Path root, GitignoreMatcher gitignore, int maxDepth, int maxFiles) {
            this.root = root;
            this.gitignore = gitignore;
            this.maxDepth = maxDepth;
            this.maxFiles = maxFiles;
        }

        void visit(Path abs) {
            Path rel = root.relativize(abs);
            // 深度限制：Files.walk 已经多走了一层（maxDepth + 1）以包含最深目录下的文件，
            // 这里二次过滤确保目录不超过 maxDepth 层。
            if (rel.getNameCount() > maxDepth + 1) return;

            boolean isDir = Files.isDirectory(abs);
            String name = abs.getFileName() == null ? "" : abs.getFileName().toString();

            // 黑名单目录（任意层）
            if (isDir && HARD_EXCLUDED_DIRS.contains(name)) return;

            // .gitignore
            if (gitignore.isIgnored(rel)) return;

            // 路径已经存在（Windows 软链场景下 walk 可能重放）—— 跳过
            if (nodes.containsKey(rel)) return;

            nodes.put(rel, new MutableNode(rel, name, isDir));

            if (!isDir) {
                allFiles.add(abs);
                String lower = name.toLowerCase(Locale.ROOT);
                if (SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
                    sourceFiles.add(abs);
                }
                if (KEY_CONFIG_FILES.containsKey(name)) {
                    keyConfigHits.putIfAbsent(rel.toString().replace('\\', '/'), abs);
                }
            }
        }

        void complete() {
            if (sourceFiles.size() > maxFiles) {
                truncated = true;
                List<Path> sorted = new ArrayList<>(sourceFiles);
                sorted.sort((a, b) -> mtimeDescCompare(a, b));
                sourceFiles.clear();
                sourceFiles.addAll(sorted.subList(0, maxFiles));
            }
        }

        FileTreeNode buildTree() {
            MutableNode rootNode = new MutableNode(root, "", true);
            // 按 path 顺序遍历，每个节点挂到它的父节点上
            // 注：Path.getParent() 对单段路径（如 "CLAUDE.md"）返回 null —— 表示没有父节点，
            // 这时应该挂到 rootNode 上。
            for (Map.Entry<Path, MutableNode> e : nodes.entrySet()) {
                Path parentPath = e.getKey().getParent();
                MutableNode parent;
                if (parentPath == null || parentPath.getNameCount() == 0) {
                    parent = rootNode;
                } else {
                    parent = nodes.get(parentPath);
                    if (parent == null) {
                        parent = rootNode;
                    }
                }
                parent.children.add(e.getValue());
            }
            return toImmutable(rootNode, 0);
        }

        private static FileTreeNode toImmutable(MutableNode n, int depth) {
            List<FileTreeNode> kids = new ArrayList<>(n.children.size());
            // 字典序：目录在前（与 ls 类似）
            n.children.sort((a, b) -> {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            for (MutableNode c : n.children) {
                kids.add(toImmutable(c, depth + 1));
            }
            return FileTreeNode.builder()
                    .name(n.name)
                    .relativePath(n.relPath.toString().replace('\\', '/'))
                    .isDirectory(n.isDir)
                    .depth(depth)
                    .children(kids)
                    .build();
        }

        private static int mtimeDescCompare(Path a, Path b) {
            try {
                FileTime ta = Files.getLastModifiedTime(a);
                FileTime tb = Files.getLastModifiedTime(b);
                return tb.compareTo(ta);
            } catch (IOException ex) {
                return 0;
            }
        }

        private static final class MutableNode {
            final Path relPath;
            final String name;
            final boolean isDir;
            final List<MutableNode> children = new ArrayList<>();

            MutableNode(Path relPath, String name, boolean isDir) {
                this.relPath = relPath;
                this.name = name;
                this.isDir = isDir;
            }
        }
    }

    // ============== 工具方法 ==============

    private static ProjectContext emptyContext(Path root) {
        return ProjectContext.builder()
                .root(root)
                .fileTree(FileTreeNode.empty())
                .sourceFileCount(0)
                .totalFileCount(0)
                .claudeMd("")
                .readme("")
                .keyConfigFiles(Collections.emptyList())
                .packageManager("unknown")
                .scannedAt(Instant.now().toEpochMilli())
                .truncated(false)
                .build();
    }

    private static GitignoreMatcher loadGitignore(Path root) {
        Path gi = root.resolve(".gitignore");
        if (!Files.exists(gi)) return GitignoreMatcher.empty();
        try {
            List<String> lines = Files.readAllLines(gi, StandardCharsets.UTF_8);
            return new GitignoreMatcher(lines);
        } catch (IOException ex) {
            log.warn("ProjectScanner: failed to read .gitignore at {}: {}", gi, ex.getMessage());
            return GitignoreMatcher.empty();
        }
    }

    private static String detectPackageManager(java.util.Set<String> keyFiles) {
        if (keyFiles.contains("pom.xml")) return "maven";
        if (keyFiles.contains("build.gradle") || keyFiles.contains("build.gradle.kts")) return "gradle";
        if (keyFiles.contains("package.json")) return "npm";
        if (keyFiles.contains("go.mod")) return "go";
        if (keyFiles.contains("Cargo.toml")) return "cargo";
        if (keyFiles.contains("pyproject.toml") || keyFiles.contains("requirements.txt")) return "pip";
        return "unknown";
    }

    private static String readText(Path path, int maxLines) {
        if (!Files.exists(path) || Files.isDirectory(path)) return "";
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            if (maxLines <= 0) {
                StringBuilder sb = new StringBuilder();
                for (String line : (Iterable<String>) () -> lines.iterator()) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String line : (Iterable<String>) () -> lines.iterator()) {
                if (n >= maxLines) break;
                sb.append(line).append('\n');
                n++;
            }
            return sb.toString();
        } catch (IOException ex) {
            log.warn("ProjectScanner: failed to read {}: {}", path, ex.getMessage());
            return "";
        }
    }
}
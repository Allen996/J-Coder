package org.example.agent.context.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectScannerTest {

    @Test
    void scansFileTreeAndLoadsClaudeMdReadme(@TempDir Path tmp) throws IOException {
        // Arrange
        Files.writeString(tmp.resolve("CLAUDE.md"), "# Project: demo\n约定: 测试约定\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("README.md"), "Line1\nLine2\n", StandardCharsets.UTF_8);
        // maxDepth=3 包含 src/main/java 这种典型 Maven 布局（3 层目录）
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Foo.java"), "package org.example;\nclass Foo {}\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"), "<project></project>\n", StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("node_modules"));
        Files.writeString(tmp.resolve("node_modules/big.js"), "// should be excluded\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve(".gitignore"), "node_modules/\ntarget/\n", StandardCharsets.UTF_8);

        // Act
        ProjectScanner scanner = new ProjectScanner();
        ProjectContext ctx = scanner.scan(tmp);

        // Assert
        assertThat(ctx.getRoot()).isNotNull();
        assertThat(ctx.getPackageManagerOrDefault()).isEqualTo("maven");
        assertThat(ctx.getClaudeMd()).contains("约定");
        assertThat(ctx.getReadme()).contains("Line1");
        assertThat(ctx.getSourceFileCount()).isEqualTo(1);
        // node_modules 必须被排除 —— totalFileCount 至少不应包含被忽略的文件
        assertThat(ctx.getTotalFileCount()).isLessThanOrEqualTo(5); // CLAUDE.md + README.md + Foo.java + pom.xml + .gitignore
        // 文件树能找到 Foo.java 但不应该有 node_modules
        assertThat(renderTree(ctx.getFileTree())).contains("Foo.java");
        assertThat(renderTree(ctx.getFileTree())).doesNotContain("node_modules");
        // pom.xml 应该被识别为 key config
        assertThat(ctx.getKeyConfigFiles()).anyMatch(k -> "pom.xml".equals(k.getRelativePath()));
    }

    @Test
    void truncatesSourceFilesWhenOver1000(@TempDir Path tmp) throws IOException {
        // Arrange: 生成 1010 个 .java 文件
        Files.createDirectories(tmp.resolve("src"));
        for (int i = 0; i < 1010; i++) {
            Path p = tmp.resolve("src/F" + i + ".java");
            Files.writeString(p, "class F" + i + " {}\n", StandardCharsets.UTF_8);
        }
        // Act
        ProjectContext ctx = new ProjectScanner().scan(tmp, 3, 1000);
        // Assert
        assertThat(ctx.isTruncated()).isTrue();
        assertThat(ctx.getSourceFileCount()).isEqualTo(1000);
    }

    @Test
    void returnsEmptyContextForMissingRoot() {
        ProjectContext ctx = new ProjectScanner().scan(Path.of("/definitely/not/here/zzz_xyz_123"));
        assertThat(ctx.getTotalFileCount()).isEqualTo(0);
        assertThat(ctx.getSourceFileCount()).isEqualTo(0);
        assertThat(ctx.getPackageManagerOrDefault()).isEqualTo("unknown");
    }

    @Test
    void detectsAllKnownPackageManagers(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("package.json"), "{}", StandardCharsets.UTF_8);
        ProjectContext ctx = new ProjectScanner().scan(tmp);
        assertThat(ctx.getPackageManagerOrDefault()).isEqualTo("npm");
    }

    @Test
    void gitignoreMatcherExcludesByPattern(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve(".gitignore"), "build/\n*.log\n/private.txt\n", StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("build"));
        Files.writeString(tmp.resolve("build/output.txt"), "x", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("app.log"), "log", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("private.txt"), "secret", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("keep.txt"), "ok", StandardCharsets.UTF_8);

        ProjectContext ctx = new ProjectScanner().scan(tmp, 3, 1000);

        assertThat(renderTree(ctx.getFileTree())).doesNotContain("build");
        assertThat(renderTree(ctx.getFileTree())).doesNotContain("app.log");
        assertThat(renderTree(ctx.getFileTree())).doesNotContain("private.txt");
        assertThat(renderTree(ctx.getFileTree())).contains("keep.txt");
    }

    private String renderTree(FileTreeNode node) {
        StringBuilder sb = new StringBuilder();
        render(node, sb);
        return sb.toString();
    }

    private void render(FileTreeNode node, StringBuilder sb) {
        if (node == null) return;
        if (node.getDepth() > 0) sb.append(node.getName()).append('\n');
        if (node.getChildren() != null) {
            for (FileTreeNode c : node.getChildren()) {
                render(c, sb);
            }
        }
    }
}
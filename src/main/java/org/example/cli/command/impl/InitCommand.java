package org.example.cli.command.impl;

import org.example.agent.context.project.ProjectContext;
import org.example.agent.context.project.ProjectContextCache;
import org.example.agent.context.project.ProjectScanner;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * /init —— 生成 CLAUDE.md 项目配置（part3.md §6.3）。
 *
 * <p>当前 v1 实现：基于 ProjectScanner 已扫到的关键配置（pom.xml / package.json / 关键目录），
 * 渲染一个最小可用模板并写入项目根的 {@code CLAUDE.md}，供下次启动自动注入到 System Layer。
 *
 * <p>幂等：文件已存在则直接报告路径，不覆盖（避免破坏用户已经改过的内容）。
 */
@Component
public class InitCommand implements SlashCommand {

    private final ProjectContextCache cache;

    public InitCommand(ProjectContextCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String description() {
        return "generate CLAUDE.md project configuration";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        Path root = ctx.projectRoot();
        Path target = root.resolve("CLAUDE.md");
        if (Files.exists(target)) {
            ctx.out().println("init: CLAUDE.md already exists at " + target + " (skipped)");
            ctx.out().flush();
            return 0;
        }

        ProjectContext project = cache.refresh(root);
        String template = renderTemplate(project);
        try {
            Files.writeString(target, template, StandardCharsets.UTF_8);
            ctx.out().println("init: wrote CLAUDE.md to " + target);
            ctx.out().flush();
            return 0;
        } catch (IOException ex) {
            ctx.out().println("init: failed to write " + target + ": " + ex.getMessage());
            ctx.out().flush();
            return 1;
        }
    }

    private String renderTemplate(ProjectContext project) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Project: ").append(safeName(project.getRoot())).append("\n\n");
        sb.append("## 约定\n");
        sb.append("- 使用 ").append(project.getPackageManagerOrDefault()).append(" 作为构建工具\n");
        sb.append("- 尽量避免直接修改生成的 entity 类\n");
        sb.append("- 所有公共 API 必须有单元测试\n\n");

        sb.append("## 常用命令\n");
        switch (project.getPackageManagerOrDefault()) {
            case "maven" -> {
                sb.append("- 测试: `mvn test`\n");
                sb.append("- 打包: `mvn clean package -DskipTests`\n");
                sb.append("- 运行: `mvn spring-boot:run`\n");
            }
            case "npm" -> {
                sb.append("- 测试: `npm test`\n");
                sb.append("- 打包: `npm run build`\n");
                sb.append("- 运行: `npm start`\n");
            }
            case "gradle" -> {
                sb.append("- 测试: `./gradlew test`\n");
                sb.append("- 打包: `./gradlew build -x test`\n");
                sb.append("- 运行: `./gradlew bootRun`\n");
            }
            default -> {
                sb.append("- 测试: 按项目实际命令填写\n");
                sb.append("- 打包: 按项目实际命令填写\n");
                sb.append("- 运行: 按项目实际命令填写\n");
            }
        }
        sb.append("\n## 关键路径\n");
        if (project.getKeyConfigFiles() != null) {
            for (ProjectContext.KeyConfigFile k : project.getKeyConfigFiles()) {
                sb.append("- ").append(k.getRelativePath()).append(" (").append(k.getType()).append(")\n");
            }
        }
        sb.append("\n## 目录结构\n");
        sb.append("(扫描根: ").append(project.getRoot()).append(", 深度 ≤ 3)\n");
        sb.append("(用 `/load` 重新扫描)\n");
        return sb.toString();
    }

    private static String safeName(Path root) {
        if (root == null) return "my-app";
        Path p = root.getFileName();
        return p == null ? "my-app" : p.toString();
    }
}
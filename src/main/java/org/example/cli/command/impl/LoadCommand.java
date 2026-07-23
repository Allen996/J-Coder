package org.example.cli.command.impl;

import org.example.agent.context.project.ProjectContext;
import org.example.agent.context.project.ProjectContextCache;
import org.example.cli.bootstrap.CliContext;
import org.example.cli.command.SlashCommand;
import org.springframework.stereotype.Component;

/**
 * /load —— 刷新 Project Layer（part3.md §6.1 Project Layer "启动 + /load 时刷新"）。
 */
@Component
public class LoadCommand implements SlashCommand {

    private final ProjectContextCache cache;

    public LoadCommand(ProjectContextCache cache) {
        this.cache = cache;
    }

    @Override
    public String name() {
        return "load";
    }

    @Override
    public String description() {
        return "rescan project and refresh Project Layer";
    }

    @Override
    public int execute(String args, CliContext ctx) {
        ProjectContext refreshed = cache.refresh(ctx.projectRoot());
        ctx.out().printf("load: rescanned %s%n", refreshed.getRoot());
        ctx.out().printf("  files: total=%d, sources=%d, truncated=%s%n",
                refreshed.getTotalFileCount(), refreshed.getSourceFileCount(), refreshed.isTruncated());
        ctx.out().printf("  package manager: %s%n", refreshed.getPackageManagerOrDefault());
        ctx.out().printf("  CLAUDE.md: %s%n",
                refreshed.getClaudeMd() == null || refreshed.getClaudeMd().isEmpty() ? "(missing)" : "loaded");
        ctx.out().printf("  README.md: %s%n",
                refreshed.getReadme() == null || refreshed.getReadme().isEmpty() ? "(missing)" : "loaded");
        if (refreshed.getKeyConfigFiles() != null && !refreshed.getKeyConfigFiles().isEmpty()) {
            ctx.out().printf("  key configs: %s%n",
                    refreshed.getKeyConfigFiles().stream()
                            .map(k -> k.getRelativePath() + "(" + k.getType() + ")")
                            .reduce((a, b) -> a + ", " + b).orElse(""));
        }
        ctx.out().flush();
        return 0;
    }
}
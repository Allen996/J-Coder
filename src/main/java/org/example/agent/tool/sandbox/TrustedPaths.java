package org.example.agent.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 加载用户配置的额外受信任目录（{@code ~/.local-cli-copilot/trusted-paths.json}）。
 *
 * <p>v1 简化：只识别每行一个路径的纯文本格式（每条非空非注释行视为一个路径），
 * 真正的 JSON 解析留给 v2。文件不存在时返回空列表 —— 静默放行项目根目录内的访问。
 *
 * <p>设计意图：白名单默认是"项目根"，trusted-paths 是 escape hatch。
 * 大多数用户永远不需要碰这个文件。
 */
@Component
public class TrustedPaths {

    private static final Logger log = LoggerFactory.getLogger(TrustedPaths.class);

    private static final String FILE_NAME = "trusted-paths.json";
    private static final String COMMENT_PREFIX = "#";

    public List<Path> load() {
        String home = System.getProperty("user.home");
        if (home == null) {
            return Collections.emptyList();
        }
        Path file = Paths.get(home, ".local-cli-copilot", FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Collections.emptyList();
        }
        try (Stream<String> lines = Files.lines(file)) {
            return lines
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith(COMMENT_PREFIX))
                    .map(this::safeResolve)
                    .filter(p -> p != null)
                    .collect(Collectors.toList());
        } catch (IOException ioe) {
            log.warn("trusted-paths.json 读取失败: {}", ioe.getMessage());
            return Collections.emptyList();
        }
    }

    private Path safeResolve(String line) {
        try {
            return Paths.get(line).toAbsolutePath().normalize();
        } catch (Exception ex) {
            log.warn("trusted-paths.json 中存在非法路径: {}", line);
            return null;
        }
    }
}

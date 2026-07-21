package org.example.agent.tool.grep;

import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.sandbox.PathGate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Grep 工具。
 *
 * <p>v1 简化：自己实现，<b>不</b>调系统 grep。原因：
 * <ul>
 *   <li>Spring AI tool callback 应该是纯 Java，避免子进程管理</li>
 *   <li>Path 已经在 PathGate 校验过，安全</li>
 *   <li>命中行数有上限（10000），避免 token 爆炸</li>
 * </ul>
 *
 * <p>性能上比 ripgrep 慢 10x 量级，但 v1 范围里项目规模可接受。
 */
@Component
public class GrepTools {

    private static final int MAX_HITS = 10000;
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024; // 10MB 单文件上限

    private final PathGate pathGate;

    public GrepTools(PathGate pathGate) {
        this.pathGate = pathGate;
    }

    @Tool(description = "在指定目录下按正则搜索文件内容。返回 path:line:content 形式。"
            + "命中超过 10000 行会被截断并提示。")
    public String grep(
            @ToolParam(description = "正则表达式（Java 风格）") String pattern,
            @ToolParam(description = "搜索根目录") String path,
            @ToolParam(description = "glob 过滤文件名，例如 '*.java'；不传则所有文件", required = false) String glob,
            @ToolParam(description = "是否忽略大小写", required = false) Boolean ignoreCase) {
        Path base = pathGate.validate(path);
        Pattern re;
        try {
            int flags = (ignoreCase != null && ignoreCase) ? Pattern.CASE_INSENSITIVE : 0;
            re = Pattern.compile(pattern, flags);
        } catch (Exception ex) {
            throw new ToolExecutionException(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "非法正则: " + ex.getMessage(),
                    "检查 pattern 语法", FailureKind.PARAM, ex);
        }

        java.nio.file.PathMatcher nameFilter = glob == null || glob.isBlank()
                ? null
                : base.getFileSystem().getPathMatcher("glob:" + glob);

        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            Iterable<Path> iter = walk::iterator;
            for (Path p : iter) {
                if (!Files.isRegularFile(p)) continue;
                if (nameFilter != null && !nameFilter.matches(p.getFileName())) continue;
                if (Files.size(p) > MAX_FILE_BYTES) continue;
                try (Stream<String> lines = Files.lines(p)) {
                    var lineIter = lines.iterator();
                    int lineNo = 0;
                    while (lineIter.hasNext()) {
                        lineNo++;
                        String line = lineIter.next();
                        if (re.matcher(line).find()) {
                            hits.add(base.relativize(p) + ":" + lineNo + ":" + line);
                            if (hits.size() >= MAX_HITS) {
                                hits.add("…(truncated, > " + MAX_HITS + " hits)");
                                return String.join("\n", hits);
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // 单文件读失败跳过
                }
            }
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "walk failed: " + ioe.getMessage(),
                    null, FailureKind.TRANSIENT, ioe);
        }
        if (hits.isEmpty()) {
            return "(no hits)";
        }
        return String.join("\n", hits);
    }
}

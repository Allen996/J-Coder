package org.example.agent.tool.file;

import org.example.agent.tool.ToolExecutionException;
import org.example.agent.tool.failure.FailureKind;
import org.example.agent.tool.failure.ToolErrorCode;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.agent.tool.sandbox.PathGate;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件类工具。
 *
 * <p>每个方法都先走 {@link PathGate#validate(String)} 做路径闸校验，再做实际 IO。
 * 异常一律抛 {@link ToolExecutionException}（带 {@link ToolErrorCode} 与
 * {@link FailureKind}），由 {@code ToolGateway} 翻译后返回给 LLM。
 *
 * <p>写操作（{@link #writeFile}, {@link #editFile}）在动盘之前通过
 * {@link SideEffectTracker#recordFileChange} 把当前内容快照进栈。这样下游工具调用
 * 因 LOGIC 失败被回滚时,文件能回到 pre-state。
 */
@Component
public class FileTools {

    /** 单次返回的内容上限。超长则截断 + 提示。 */
    private static final int MAX_CONTENT_BYTES = 100 * 1024;

    private final PathGate pathGate;
    private final SideEffectTracker sideEffects;

    public FileTools(PathGate pathGate, SideEffectTracker sideEffects) {
        this.pathGate = pathGate;
        this.sideEffects = sideEffects;
    }

    @Tool(description = "读取文件指定行范围。startLine/endLine 都是 1-based，包含两端。"
            + "不传 startLine/endLine 时读整个文件。超长文件会被截断并提示。")
    public String readFile(
            @ToolParam(description = "绝对路径或项目相对路径") String path,
            @ToolParam(description = "起始行（包含），1-based；缺省从第 1 行开始", required = false) Integer startLine,
            @ToolParam(description = "结束行（包含），1-based；缺省到最后一行", required = false) Integer endLine) {
        Path real = pathGate.validate(path);
        try {
            List<String> lines = Files.readAllLines(real, StandardCharsets.UTF_8);
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? lines.size() : Math.min(lines.size(), endLine);
            if (from > lines.size()) {
                throw new ToolExecutionException(
                        ToolErrorCode.INVALID_ARGUMENT,
                        "startLine " + from + " 超过文件总行数 " + lines.size(),
                        "startLine 必须 <= 文件总行数");
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from - 1; i < to; i++) {
                sb.append(i + 1).append('\t').append(lines.get(i)).append('\n');
            }
            String content = sb.toString();
            if (content.length() > MAX_CONTENT_BYTES) {
                String truncated = content.substring(0, MAX_CONTENT_BYTES);
                return truncated + "\n…(truncated, total " + lines.size() + " lines, use startLine/endLine to read range)";
            }
            return content;
        } catch (IOException ioe) {
            if (ioe instanceof java.nio.file.NoSuchFileException) {
                throw new ToolExecutionException(
                        ToolErrorCode.PATH_NOT_FOUND,
                        "path not found: " + path,
                        "确认路径是否存在", ioe);
            }
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "read failed: " + ioe.getMessage(),
                    "重试或检查文件权限", FailureKind.TRANSIENT, ioe);
        }
    }

    @Tool(description = "覆盖写入文件内容。文件不存在则创建。")
    public String writeFile(
            @ToolParam(description = "绝对路径或项目相对路径") String path,
            @ToolParam(description = "要写入的完整内容") String content) {
        Path real = pathGate.validate(path);
        try {
            // 写之前拍快照: 文件不存在 → pre-state 为 null,rollback 时执行 delete
            byte[] preState = Files.exists(real) ? Files.readAllBytes(real) : null;
            sideEffects.recordFileChange("writeFile", path, preState);

            Path parent = real.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(real, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return "wrote " + content.length() + " bytes to " + real;
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "write failed: " + ioe.getMessage(),
                    "重试或检查父目录权限", FailureKind.TRANSIENT, ioe);
        }
    }

    @Tool(description = "在文件中查找 oldText 并替换为 newText。要求 oldText 唯一匹配，否则报错。")
    public String editFile(
            @ToolParam(description = "绝对路径或项目相对路径") String path,
            @ToolParam(description = "要被替换的原文（必须唯一匹配）") String oldText,
            @ToolParam(description = "替换后的新内容") String newText) {
        Path real = pathGate.validate(path);
        try {
            // 必须先读 original 才能匹配 → 这步同时充当快照源。注意此时还没修改文件,
            // 即使后续 oldText 没匹配抛出 PARAM,栈里的快照是无害的(rollback 还原成同一内容 = no-op)。
            byte[] preState = Files.readAllBytes(real);
            sideEffects.recordFileChange("editFile", path, preState);

            String original = new String(preState, StandardCharsets.UTF_8);
            int firstIdx = original.indexOf(oldText);
            if (firstIdx < 0) {
                throw new ToolExecutionException(
                        ToolErrorCode.WRITE_FILE_CONFLICT,
                        "oldText not found in file",
                        "先 read_file 确认原文内容",
                        FailureKind.PARAM);
            }
            int secondIdx = original.indexOf(oldText, firstIdx + oldText.length());
            if (secondIdx >= 0) {
                throw new ToolExecutionException(
                        ToolErrorCode.WRITE_FILE_CONFLICT,
                        "oldText 出现多次，无法确定替换位置",
                        "提供更具体的 oldText（含更多上下文）",
                        FailureKind.PARAM);
            }
            String updated = original.substring(0, firstIdx)
                    + newText
                    + original.substring(firstIdx + oldText.length());
            Files.writeString(real, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return "edited " + real + " (" + oldText.length() + " -> " + newText.length() + " bytes)";
        } catch (ToolExecutionException tee) {
            throw tee;
        } catch (IOException ioe) {
            if (ioe instanceof java.nio.file.NoSuchFileException) {
                throw new ToolExecutionException(
                        ToolErrorCode.PATH_NOT_FOUND,
                        "path not found: " + path,
                        "确认路径是否存在", ioe);
            }
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "edit failed: " + ioe.getMessage(),
                    "重试或检查文件权限", FailureKind.TRANSIENT, ioe);
        }
    }

    @Tool(description = "列出目录内容（默认深度 1）。返回 name/type/size 三列。")
    public String listDir(
            @ToolParam(description = "绝对路径或项目相对路径") String path,
            @ToolParam(description = "递归深度，1 表示只列当前目录", required = false) Integer depth) {
        Path real = pathGate.validate(path);
        int d = depth == null ? 1 : Math.max(0, depth);
        try {
            StringBuilder sb = new StringBuilder();
            appendDir(sb, real, "", d);
            return sb.toString();
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "list failed: " + ioe.getMessage(),
                    null, FailureKind.TRANSIENT, ioe);
        }
    }

    @Tool(description = "按 glob 模式匹配文件。pattern 如 '**/*.java'。path 不传则从项目根开始。")
    public String globFiles(
            @ToolParam(description = "glob 模式，例如 '**/*.java' 或 'src/**/*.md'") String pattern,
            @ToolParam(description = "搜索根目录，绝对或项目相对；缺省项目根", required = false) String path) {
        Path base = path == null || path.isBlank()
                ? pathGate.getProjectRoot()
                : pathGate.validate(path);
        java.nio.file.PathMatcher matcher = base.getFileSystem().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(matcher::matches)
                .limit(1000)
                .forEach(p -> matches.add(base.relativize(p).toString()));
        } catch (IOException ioe) {
            throw new ToolExecutionException(
                    ToolErrorCode.IO_TRANSIENT,
                    "glob failed: " + ioe.getMessage(),
                    null, FailureKind.TRANSIENT, ioe);
        }
        if (matches.isEmpty()) {
            return "(no matches)";
        }
        return String.join("\n", matches);
    }

    // ============== 内部 ==============

    private void appendDir(StringBuilder sb, Path dir, String prefix, int remainingDepth) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted().forEach(child -> {
                String name = prefix.isEmpty() ? child.getFileName().toString()
                        : prefix + "/" + child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    sb.append(name).append("/\n");
                    if (remainingDepth > 1) {
                        try {
                            appendDir(sb, child, name, remainingDepth - 1);
                        } catch (IOException ignored) {
                            // 子目录不可读时跳过
                        }
                    }
                } else {
                    try {
                        sb.append(name).append("\t").append(Files.size(child)).append("\n");
                    } catch (IOException ignored) {
                        sb.append(name).append("\t?\n");
                    }
                }
            });
        }
    }
}

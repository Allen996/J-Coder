package org.example.cli.input;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把用户输入中的 "@path/to/file" 引用解析为 (path, foldedContent) 列表，
 * 并生成可以发给 LLM 的展开 prompt。
 *
 * 行为：
 *  - 识别 token 形式的 @path（不在引号内也行；引号内整段算一个 token）
 *  - 排除 "开头" 是 @@ 的转义
 *  - 路径支持绝对路径或相对项目根
 *  - 路径尾部标点 ,.;:!?) 会被剥除（"@Foo.java," 能正确识别）
 *  - 文件 > 50KB → 折叠为首 200 行 + 省略标记 + 末 200 行
 *  - 文件不存在 / 是目录 → 该 token 不解析（保留在 remaining 文本里）
 *
 * Tokenizer 正则：("[^"]*"|'[^']*'|\S+)  保持引号内空白，引号外按空白分词
 */
@Slf4j
@Component
public class AtFileResolver {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"[^\"]*\"|'[^']*'|\\S+");
    private static final long FOLD_THRESHOLD_BYTES = 50_000L;
    private static final int FOLD_HEAD_LINES = 200;
    private static final int FOLD_TAIL_LINES = 200;
    private static final String TRAILING_PUNCT = ",.;:!?)";

    public record AtFileRef(Path absolutePath, String foldedContent, int lineCount) {
    }

    public record Resolved(List<AtFileRef> refs, String remainingText, int unresolvedCount) {
    }

    public Resolved resolve(String raw, Path projectRoot) {
        if (raw == null || raw.isBlank()) {
            return new Resolved(List.of(), "", 0);
        }
        List<AtFileRef> refs = new ArrayList<>();
        List<String> remainingTokens = new ArrayList<>();
        int unresolved = 0;

        Matcher m = TOKEN_PATTERN.matcher(raw);
        while (m.find()) {
            String token = m.group();
            AtFileRef ref = tryParse(token, projectRoot);
            if (ref != null) {
                refs.add(ref);
            } else {
                if (looksLikeAtFile(token)) {
                    unresolved++;
                }
                remainingTokens.add(token);
            }
        }

        return new Resolved(refs, String.join(" ", remainingTokens), unresolved);
    }

    /**
     * 把 AtFileRef 列表 + 剩余文本拼成最终发给 LLM 的 prompt。
     * 形如：
     *   @src/Foo.java (30 lines):
     *   ```
     *   <folded>
     *   ```
     *   @src/Bar.java (...):
     *   ...
     *
     *   <remainingText>
     */
    public String buildPrompt(Resolved resolved, Path projectRoot) {
        StringBuilder sb = new StringBuilder();
        for (AtFileRef ref : resolved.refs()) {
            String rel = relativize(ref.absolutePath(), projectRoot);
            sb.append("@").append(rel).append(" (").append(ref.lineCount()).append(" lines):\n");
            sb.append("```\n").append(ref.foldedContent()).append("\n```\n\n");
        }
        if (resolved.remainingText() != null && !resolved.remainingText().isBlank()) {
            if (!resolved.refs().isEmpty()) {
                sb.append("\n");
            }
            sb.append(resolved.remainingText());
        }
        return sb.toString();
    }

    private AtFileRef tryParse(String token, Path projectRoot) {
        if (!looksLikeAtFile(token)) {
            return null;
        }
        // token 形如 @path 或 @"path with space"
        String body = token;
        // 去掉两端引号
        if ((body.startsWith("\"") && body.endsWith("\"")) ||
                (body.startsWith("'") && body.endsWith("'"))) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.startsWith("@@")) {
            return null; // escape
        }
        if (body.length() < 2) {
            return null;
        }
        body = body.substring(1); // 去掉 '@'
        // 剥离尾部标点
        while (!body.isEmpty() && TRAILING_PUNCT.indexOf(body.charAt(body.length() - 1)) >= 0) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isEmpty()) {
            return null;
        }
        Path abs;
        try {
            Path p = Path.of(body);
            abs = p.isAbsolute() ? p.normalize() : projectRoot.resolve(body).normalize();
        } catch (Exception ex) {
            return null;
        }
        if (!Files.exists(abs) || Files.isDirectory(abs)) {
            return null;
        }
        try {
            String content = readFolded(abs);
            int lines = countLines(abs);
            return new AtFileRef(abs, content, lines);
        } catch (IOException ex) {
            log.warn("Failed to read @file {}: {}", abs, ex.getMessage());
            return null;
        }
    }

    private boolean looksLikeAtFile(String token) {
        if (token == null || token.isEmpty()) return false;
        // 引号包裹但内部以 @ 开头
        if ((token.startsWith("\"@") && token.length() >= 3) ||
                (token.startsWith("'@") && token.length() >= 3)) {
            return true;
        }
        // 不带引号直接以 @ 开头且不是 @@
        if (token.startsWith("@") && !token.startsWith("@@")) {
            return true;
        }
        return false;
    }

    private String readFolded(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= FOLD_THRESHOLD_BYTES) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        // 大文件：首 200 行 + 末 200 行
        List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int total = allLines.size();
        int head = Math.min(FOLD_HEAD_LINES, total);
        int tail = Math.min(FOLD_TAIL_LINES, Math.max(0, total - head));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head; i++) {
            sb.append(allLines.get(i)).append('\n');
        }
        int omitted = Math.max(0, total - head - tail);
        if (omitted > 0) {
            sb.append("…(").append(omitted).append(" lines omitted)…\n");
        }
        for (int i = total - tail; i < total; i++) {
            sb.append(allLines.get(i)).append('\n');
        }
        return sb.toString();
    }

    private int countLines(Path path) throws IOException {
        try (var stream = Files.lines(path, StandardCharsets.UTF_8)) {
            return (int) stream.count();
        }
    }

    private String relativize(Path abs, Path projectRoot) {
        try {
            return projectRoot.toAbsolutePath().relativize(abs.toAbsolutePath()).toString().replace('\\', '/');
        } catch (Exception ex) {
            return abs.toString();
        }
    }
}
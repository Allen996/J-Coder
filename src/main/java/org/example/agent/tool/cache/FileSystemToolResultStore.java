package org.example.agent.tool.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.example.agent.tool.config.CliToolProperties;
import org.example.agent.tool.spi.ToolDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认实现:文件存储 + 后台清理。
 *
 * <p>目录结构:
 * <pre>
 *   &lt;projectRoot&gt;/.agent-cache/tool-results/
 *     tr-abc12345.json
 *     tr-def67890.json
 * </pre>
 *
 * <p>线程模型:
 * <ul>
 *   <li>save:由调用方在 toolExecutor 上触发,文件 IO 是阻塞的
 *   <li>recall:由 ToolGateway/ReActLoop 同步触发
 *   <li>后台清理:单线程 ScheduledExecutorService,每 5 分钟跑一次 evictIfOverBudget
 * </ul>
 *
 * <p>stale 检查策略:写入时记录 watchedPaths 的 mtime(毫秒);recall 时重新读 mtime 对比。
 * mtime 不一样即视为失效。不做内容 hash 因为大文件 IO 太重。
 */
@Component
public class FileSystemToolResultStore implements ToolResultStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemToolResultStore.class);

    /** id 格式校验:`#<id>` 出现在文本里时,id 必须匹配。recall 也用同一正则做白名单。 */
    public static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9]{8}$");
    /** 文本中扫占位符用,允许前缀 `#`。 */
    public static final Pattern ID_IN_TEXT = Pattern.compile("#([a-z0-9]{8})");

    /** 文本中可能带行号前缀(形如 {@code 123\tcontent}),过滤时剥离。 */
    private static final Pattern LINE_NUMBER_PREFIX = Pattern.compile("^\\d+\\t");

    /** 工具结果里行号分隔符:read_file 是 {@code N\tcontent}。其他工具无此格式时正则不命中,安全。 */
    private static final Pattern HAS_LINE_NUMBER = Pattern.compile("^\\d+\\t", Pattern.MULTILINE);

    private static final String CACHE_DIR_NAME = ".agent-cache/tool-results";
    private static final String FILE_PREFIX = "tr-";
    private static final String FILE_SUFFIX = ".json";

    /** Base32 alphabet (RFC 4648, lowercase) — 排除易混淆的 0/o/1/l。 */
    private static final char[] BASE32_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    private final ObjectMapper objectMapper;
    private final Path projectRoot;
    private final Path baseDir;
    private final CliToolProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tool-result-cache-cleaner");
                t.setDaemon(true);
                return t;
            });

    public FileSystemToolResultStore(@Value("${cli.project-root:}") String projectRootConfig,
                                     CliToolProperties properties) {
        this.properties = properties;
        this.projectRoot = (projectRootConfig == null || projectRootConfig.isBlank())
                ? Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
                : Paths.get(projectRootConfig).toAbsolutePath().normalize();
        this.baseDir = projectRoot.resolve(CACHE_DIR_NAME);
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(baseDir);
        // 每 5 分钟检查一次总量
        cleaner.scheduleAtFixedRate(this::evictIfOverBudget, 5, 5, TimeUnit.MINUTES);
        log.info("ToolResultStore initialized at {}", baseDir);
    }

    @PreDestroy
    void shutdown() {
        cleaner.shutdownNow();
    }

    public Path getBaseDir() {
        return baseDir;
    }

    // ============== save ==============

    @Override
    public String save(String toolName,
                       Map<String, Object> args,
                       String result,
                       String executionId,
                       ToolDescriptor descriptor) {
        if (result == null) return null;
        String id = generateId();
        try {
            List<String> watchedPaths = inferWatchedPaths(toolName, args);
            List<String> watchedHashes = computeCurrentHashes(watchedPaths);
            Instant now = Instant.now();
            Instant expiresAt = now.plus(properties.resultCache().ttl());

            ToolResultRecord record = new ToolResultRecord(
                    id,
                    toolName,
                    args == null ? Map.of() : args,
                    result,
                    now,
                    expiresAt,
                    result.getBytes(StandardCharsets.UTF_8).length,
                    watchedPaths,
                    watchedHashes
            );
            Path file = baseDir.resolve(FILE_PREFIX + id + FILE_SUFFIX);
            String json = objectMapper.writeValueAsString(record);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("ToolResultStore.save id={} tool={} size={}B watched={}",
                    id, toolName, record.resultSizeBytes(), watchedPaths);
            return id;
        } catch (Exception ex) {
            log.warn("ToolResultStore.save failed for tool={}: {}", toolName, ex.getMessage());
            return null;
        }
    }

    // ============== recall ==============

    @Override
    public RecallResult recall(String id, Integer startLine, Integer endLine, String pattern) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            return new RecallResult.InvalidId(id);
        }
        Path file = baseDir.resolve(FILE_PREFIX + id + FILE_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return new RecallResult.NotFound(id);
        }
        ToolResultRecord record;
        try {
            record = objectMapper.readValue(file.toFile(), ToolResultRecord.class);
        } catch (IOException ioe) {
            log.warn("ToolResultStore.recall failed to parse {}: {}", file, ioe.getMessage());
            return new RecallResult.NotFound(id);
        }

        Instant now = Instant.now();
        if (record.expiresAt() != null && now.isAfter(record.expiresAt())) {
            deleteQuietly(file, "expired");
            return new RecallResult.Expired(id);
        }
        if (record.watchedPaths() != null && !record.watchedPaths().isEmpty()) {
            List<String> current = computeCurrentHashes(record.watchedPaths());
            if (!current.equals(record.watchedHashes())) {
                deleteQuietly(file, "stale");
                return new RecallResult.StaleRemoved(id);
            }
        }

        String filtered = applyFilter(record.result(), startLine, endLine, pattern);
        return new RecallResult.Ok(filtered);
    }

    // ============== invalidateByPath ==============

    @Override
    public void invalidateByPath(Path path) {
        if (path == null) return;
        String target = path.toAbsolutePath().normalize().toString();
        if (!Files.isDirectory(baseDir)) return;
        try (var stream = Files.list(baseDir)) {
            List<Path> toDelete = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().startsWith(FILE_PREFIX))
                    .forEach(p -> {
                        try {
                            ToolResultRecord rec = objectMapper.readValue(p.toFile(), ToolResultRecord.class);
                            if (rec.watchedPaths() != null) {
                                for (String wp : rec.watchedPaths()) {
                                    if (wp != null && wp.equals(target)) {
                                        toDelete.add(p);
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ignore) {
                            // 解析失败的单条不影响其他清理
                        }
                    });
            for (Path p : toDelete) {
                deleteQuietly(p, "invalidateByPath " + target);
            }
            if (!toDelete.isEmpty()) {
                log.info("ToolResultStore invalidated {} records by path {}", toDelete.size(), target);
            }
        } catch (IOException ioe) {
            log.warn("ToolResultStore.invalidateByPath failed: {}", ioe.getMessage());
        }
    }

    // ============== scanIds ==============

    @Override
    public List<String> scanIds(String text) {
        if (text == null || text.isEmpty()) return List.of();
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Matcher m = ID_IN_TEXT.matcher(text);
        while (m.find()) {
            ordered.add(m.group(1));
        }
        return new ArrayList<>(ordered);
    }

    // ============== metadataHint ==============

    @Override
    public String metadataHint(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            return "#" + id + " (invalid id)";
        }
        Path file = baseDir.resolve(FILE_PREFIX + id + FILE_SUFFIX);
        if (!Files.isRegularFile(file)) {
            return "#" + id + " (not found)";
        }
        try {
            ToolResultRecord rec = objectMapper.readValue(file.toFile(), ToolResultRecord.class);
            return String.format("#%s  %s(args=%s)  |  %d B  |  watched=%s  |  recall with startLine/endLine/pattern to load specific parts",
                    rec.id(), rec.toolName(), rec.toolArgs(),
                    rec.resultSizeBytes(),
                    rec.watchedPaths() == null ? "[]" : rec.watchedPaths().toString());
        } catch (IOException ioe) {
            return "#" + id + " (parse error)";
        }
    }

    // ============== evictIfOverBudget ==============

    @Override
    public void evictIfOverBudget() {
        long max = properties.resultCache().maxTotalBytes();
        if (!Files.isDirectory(baseDir)) return;
        try (var stream = Files.list(baseDir)) {
            List<Path> files = new ArrayList<>();
            long total = 0;
            for (var it = stream.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (p.getFileName().toString().startsWith(FILE_PREFIX)) {
                    files.add(p);
                    total += Files.size(p);
                }
            }
            if (total <= max) {
                log.debug("ToolResultStore cache size {} B <= budget {} B", total, max);
                return;
            }
            // 按 capturedAt LRU 删 —— 一次只删一批,避免长 GC
            record FileMeta(Path path, Instant capturedAt, long size) {}
            List<FileMeta> metas = new ArrayList<>(files.size());
            for (Path p : files) {
                try {
                    ToolResultRecord r = objectMapper.readValue(p.toFile(), ToolResultRecord.class);
                    metas.add(new FileMeta(p, r.capturedAt(), Files.size(p)));
                } catch (Exception ignore) {}
            }
            metas.sort((a, b) -> a.capturedAt().compareTo(b.capturedAt()));
            long freed = 0;
            int deleted = 0;
            for (FileMeta m : metas) {
                if (total - freed <= max) break;
                try {
                    Files.deleteIfExists(m.path());
                    freed += m.size();
                    deleted++;
                } catch (IOException ioe) {
                    log.warn("evict delete failed {}: {}", m.path(), ioe.getMessage());
                }
            }
            log.info("ToolResultStore eviction: deleted {} files, freed {} B (was {} B, budget {} B)",
                    deleted, freed, total, max);
        } catch (IOException ioe) {
            log.warn("ToolResultStore.evictIfOverBudget failed: {}", ioe.getMessage());
        }
    }

    // ============== 内部 ==============

    private String generateId() {
        char[] chars = new char[8];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = BASE32_ALPHABET[random.nextInt(BASE32_ALPHABET.length)];
        }
        return new String(chars);
    }

    /**
     * 根据工具名和参数推断 watched 路径。
     * file 类工具直接取 path;grep/glob 取 path 或 base;git 类不追踪(依赖 git 内部状态)。
     */
    private List<String> inferWatchedPaths(String toolName, Map<String, Object> args) {
        if (args == null) return List.of();
        return switch (toolName) {
            case "read_file", "write_file", "edit_file", "list_dir" -> {
                Object p = args.get("path");
                yield (p instanceof String s && !s.isBlank()) ? List.of(absolute(s)) : List.of();
            }
            case "glob_files", "grep" -> {
                Object p = args.get("path");
                if (p instanceof String s && !s.isBlank()) {
                    yield List.of(absolute(s));
                }
                yield List.of(absolute("."));   // 项目根兜底
            }
            case "git_show", "git_diff", "git_log", "git_status", "git_commit" -> List.of();
            case "run_shell", "check_command_exists" -> List.of();
            default -> List.of();
        };
    }

    /** 解析 watched paths 当前 mtime(毫秒)。失败的路径用空字符串,比较时视为失效。 */
    private List<String> computeCurrentHashes(List<String> paths) {
        if (paths == null || paths.isEmpty()) return List.of();
        List<String> hashes = new ArrayList<>(paths.size());
        for (String p : paths) {
            try {
                FileTime ft = Files.getLastModifiedTime(Paths.get(p));
                hashes.add(Long.toString(ft.toMillis()));
            } catch (IOException ioe) {
                hashes.add("");   // 不可访问 = 视为已变
            }
        }
        return hashes;
    }

    private String absolute(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) return p.normalize().toString();
        return projectRoot.resolve(path).normalize().toString();
    }

    private void deleteQuietly(Path file, String reason) {
        try {
            boolean ok = Files.deleteIfExists(file);
            log.debug("ToolResultStore deleted {} ({})", file.getFileName(), reason);
        } catch (IOException ioe) {
            log.warn("ToolResultStore delete failed {}: {}", file, ioe.getMessage());
        }
    }

    /**
     * 过滤实现:
     * <ul>
     *   <li>三个过滤器(startLine/endLine/pattern)都给 null → 返回原内容(剥离行号)</li>
     *   <li>行范围:按 \n split,剥离 {@code N\t} 前缀,切片,返回不带行号</li>
     *   <li>正则:匹配每行(剥离行号后),输出匹配行 + ±2 行 context,封顶 100 行,不带行号</li>
     * </ul>
     */
    private String applyFilter(String content, Integer startLine, Integer endLine, String pattern) {
        if (content == null || content.isEmpty()) return "";

        boolean hasLineNumbers = HAS_LINE_NUMBER.matcher(content).find();
        String[] rawLines = content.split("\\n", -1);

        // 先统一剥离行号,得到"纯行"列表
        List<String> stripped = new ArrayList<>(rawLines.length);
        for (String line : rawLines) {
            if (hasLineNumbers) {
                stripped.add(LINE_NUMBER_PREFIX.matcher(line).replaceFirst(""));
            } else {
                stripped.add(line);
            }
        }

        if (pattern != null && !pattern.isEmpty()) {
            return applyRegexFilter(stripped, pattern);
        }
        if (startLine != null || endLine != null) {
            int from = (startLine == null) ? 1 : Math.max(1, startLine);
            int to = (endLine == null) ? stripped.size() : Math.min(stripped.size(), endLine);
            if (from > stripped.size()) {
                return "(startLine " + from + " exceeds content length " + stripped.size() + ")";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = from - 1; i < to; i++) {
                if (i > from - 1) sb.append('\n');
                sb.append(stripped.get(i));
            }
            return sb.toString();
        }
        // 都不传:返回全部(已剥离行号)
        return String.join("\n", stripped);
    }

    private static final int REGEX_MAX_LINES = 100;
    private static final int REGEX_CONTEXT = 2;

    private String applyRegexFilter(List<String> lines, String pattern) {
        Pattern p;
        try {
            p = Pattern.compile(pattern, Pattern.MULTILINE);
        } catch (Exception ex) {
            return "(invalid regex: " + ex.getMessage() + ")";
        }
        boolean[] matched = new boolean[lines.size()];
        int matchCount = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).find()) {
                matched[i] = true;
                matchCount++;
                if (matchCount >= REGEX_MAX_LINES) break;
            }
        }
        if (matchCount == 0) {
            return "(no matches for pattern: " + pattern + ")";
        }
        boolean[] inResult = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (matched[i]) {
                int from = Math.max(0, i - REGEX_CONTEXT);
                int to = Math.min(lines.size() - 1, i + REGEX_CONTEXT);
                for (int j = from; j <= to; j++) inResult[j] = true;
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < lines.size(); i++) {
            if (!inResult[i]) continue;
            if (!first) sb.append('\n');
            sb.append(lines.get(i));
            first = false;
        }
        return sb.toString();
    }
}

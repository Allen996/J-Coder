package org.example.agent.core.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.memory.LongTermStore;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.dag.DagGraph;
import org.example.agent.core.task.dag.DagState;
import org.example.agent.core.task.dag.DagStateRepository;
import org.example.agent.core.task.orchestrator.TaskOrchestrator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checkpoint 写入器（阶段 4 引入）。
 *
 * <p>职责：
 * <ul>
 *   <li>调 {@code git rev-parse HEAD} 拿当前 commit（不可用时拒绝写入）</li>
 *   <li>收集主 Agent 完整状态：system prompt / 工具 registry / DAG / worklog / mid-term / long-term</li>
 *   <li>原子写 {@code .agent/sessions/{sessionId}/checkpoints/{seq}-{reason}/manifest.json}</li>
 *   <li>应用用户决策（status → DECIDED / ABANDONED）</li>
 *   <li>追踪当前 session 的 Checkpoint 序号（nextSeq）</li>
 * </ul>
 *
 * <p>设计：
 * <ul>
 *   <li>Checkpoints 目录独立于 plan.json / dag-state.json，与 mid-term/short-term 同级</li>
 *   <li>{@link #gitAvailable()} 单独暴露,供测试覆盖</li>
 *   <li>{@link #nextSeq(String)} 按 session 顺序递增 —— 读现有 checkpoints 目录取 max+1</li>
 * </ul>
 */
@Slf4j
@Component
public class CheckpointService {

    private final Path sessionsRoot;
    private final ObjectMapper mapper;
    private final SessionMessageStore sessionStore;
    private final DagStateRepository dagStateRepository;
    private final LongTermStore longTermStore;
    private final TaskOrchestrator orchestrator;
    /** 项目根目录（用于 git rev-parse）。可被覆盖（测试）。 */
    private final Path projectRoot;
    /** git 可执行路径；null 表示自动探测。 */
    private String gitBinary;
    /** 当前 session 最近的 checkpoint id（in-memory），提供给 SubAgent 留引用。 */
    @Getter
    private volatile String currentCheckpointId = "";

    @Autowired
    public CheckpointService(
            @Value("${cli.project-root:}") String projectRootConfig,
            SessionMessageStore sessionStore,
            DagStateRepository dagStateRepository,
            LongTermStore longTermStore,
            @Lazy TaskOrchestrator orchestrator) {
        Path root = projectRootConfig == null || projectRootConfig.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(projectRootConfig);
        this.projectRoot = root.toAbsolutePath().normalize();
        this.sessionsRoot = this.projectRoot.resolve(".agent").resolve("sessions");
        this.sessionStore = sessionStore;
        this.dagStateRepository = dagStateRepository;
        this.longTermStore = longTermStore;
        this.orchestrator = orchestrator;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.gitBinary = detectGitBinary();
    }

    /** 测试入口：自定义 root + gitBinary + 可选 orchestrator。 */
    public CheckpointService(Path projectRoot, String gitBinary,
                              SessionMessageStore sessionStore,
                              DagStateRepository dagStateRepository,
                              LongTermStore longTermStore,
                              TaskOrchestrator orchestrator) {
        this.projectRoot = projectRoot == null ? Path.of(".") : projectRoot.toAbsolutePath().normalize();
        this.sessionsRoot = this.projectRoot.resolve(".agent").resolve("sessions");
        this.sessionStore = sessionStore;
        this.dagStateRepository = dagStateRepository;
        this.longTermStore = longTermStore;
        this.orchestrator = orchestrator;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.gitBinary = gitBinary == null || gitBinary.isBlank() ? detectGitBinary() : gitBinary;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(sessionsRoot);
        } catch (IOException ex) {
            log.warn("CheckpointService: mkdir {} failed: {}", sessionsRoot, ex.getMessage());
        }
    }

    /** 测试可覆盖 git 二进制路径。 */
    public void setGitBinary(String binary) {
        this.gitBinary = binary;
    }

    public Path projectRoot() { return projectRoot; }
    public Path sessionsRoot() { return sessionsRoot; }

    // ==================== Git ====================

    /**
     * 调 {@code git rev-parse HEAD} 拿当前 commit hash。
     * 不可用（无 git / 非仓库 / IO 异常）时抛 {@link GitUnavailableException}。
     */
    public String currentGitCommit() {
        if (gitBinary == null || gitBinary.isBlank()) {
            throw new GitUnavailableException("git binary not found on PATH");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(gitBinary, "rev-parse", "HEAD")
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            int rc = p.waitFor();
            if (rc != 0) {
                throw new GitUnavailableException("git rev-parse HEAD exit=" + rc
                        + " out=" + new String(out, StandardCharsets.UTF_8).trim());
            }
            String hash = new String(out, StandardCharsets.UTF_8).trim();
            if (hash.isEmpty()) {
                throw new GitUnavailableException("git rev-parse HEAD returned empty");
            }
            return hash;
        } catch (IOException ex) {
            throw new GitUnavailableException("io: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitUnavailableException("interrupted", ex);
        }
    }

    /** 是否能拿到 git commit（不抛异常的便捷检测）。 */
    public boolean gitAvailable() {
        try {
            currentGitCommit();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String detectGitBinary() {
        // 简易探测 —— Windows 上 git.exe 在 PATH 中。
        String os = System.getProperty("os.name", "").toLowerCase();
        String name = os.contains("win") ? "git.exe" : "git";
        // 我们不实际 spawn 探测 —— 留到 currentGitCommit() 用 ProcessBuilder 触发。
        // 如果 binary 不存在,ProcessBuilder 会抛 IOException —— 视作 unavailable。
        return name;
    }

    // ==================== 写入 ====================

    /**
     * 写入一个新 Checkpoint（status=PENDING_DECISION），返回 Checkpoint 实例与写入路径。
     *
     * @throws GitUnavailableException git 不可用时
     */
    public Result createPending(String sessionId, String reason, String decisionQuestion,
                                List<String> relevantArtifacts,
                                String systemPrompt,
                                List<String> toolRegistry,
                                DagGraph dagGraph,
                                DagState dagState,
                                String parentCheckpointId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
        String gitCommit = currentGitCommit();
        int seq = nextSeq(sessionId);
        String slug = Checkpoint.slugifyReason(reason);
        String checkpointId = String.format("%03d-%s", seq, slug);

        // 收集 mid-term / long-term 元数据
        Integer worklogTailSeq = sessionStore != null ? sessionStore.worklogSize(sessionId) : null;
        Integer midTermSeq = null;
        if (sessionStore != null) {
            // mid-term 消息条数 = mid-term.json 解析后的 messages.length
            midTermSeq = countMidTermRecords(sessionStore, sessionId);
        }
        List<String> longTermFiles = collectLongTermFiles();

        Checkpoint cp = Checkpoint.pending(
                checkpointId, seq, sessionId, reason,
                decisionQuestion == null ? "" : decisionQuestion,
                relevantArtifacts == null ? List.of() : relevantArtifacts,
                gitCommit,
                systemPrompt == null ? "" : systemPrompt,
                toolRegistry == null ? List.of() : toolRegistry,
                dagGraphJson(dagGraph),
                dagStateJson(dagState),
                worklogTailSeq,
                midTermSeq,
                longTermFiles,
                parentCheckpointId == null ? currentCheckpointId : parentCheckpointId,
                dagGraph != null ? dagGraph.getPlanId() : "");

        Path dir = checkpointDir(sessionId, checkpointId);
        Path manifest = writeManifest(dir, cp);
        this.currentCheckpointId = checkpointId;
        // 阶段 4:同步给 TaskOrchestrator,后续 dispatch_subtask 自动继承
        if (orchestrator != null) {
            try {
                orchestrator.setCurrentCheckpointId(checkpointId);
            } catch (RuntimeException ex) {
                log.warn("setCurrentCheckpointId failed: {}", ex.getMessage());
            }
        }
        log.info("checkpoint created: id={} session={} seq={} reason='{}' git={}",
                checkpointId, sessionId, seq, reason, gitCommit.substring(0, Math.min(8, gitCommit.length())));
        return new Result(cp, manifest);
    }

    /** 应用用户决策（PENDING_DECISION → DECIDED）。 */
    public Checkpoint applyDecision(String sessionId, String checkpointId,
                                    String decisionText, String decidedBy) {
        Path manifest = manifestPath(sessionId, checkpointId);
        Checkpoint existing = readManifest(manifest)
                .orElseThrow(() -> new IllegalArgumentException(
                        "checkpoint manifest not found: " + manifest));
        if (!Checkpoint.STATUS_PENDING_DECISION.equals(existing.getStatus())) {
            log.warn("applyDecision: checkpoint {} already status={}; overwriting anyway",
                    checkpointId, existing.getStatus());
        }
        Checkpoint updated = existing.withDecision(decisionText, decidedBy);
        writeManifest(manifest.getParent(), updated);
        log.info("checkpoint decision applied: id={} session={} by={}",
                checkpointId, sessionId, decidedBy);
        return updated;
    }

    /** 标记 ABANDONED。 */
    public Checkpoint abandon(String sessionId, String checkpointId, String note) {
        Path manifest = manifestPath(sessionId, checkpointId);
        Checkpoint existing = readManifest(manifest)
                .orElseThrow(() -> new IllegalArgumentException(
                        "checkpoint manifest not found: " + manifest));
        Checkpoint updated = existing.withAbandoned(note);
        writeManifest(manifest.getParent(), updated);
        log.info("checkpoint abandoned: id={} session={}", checkpointId, sessionId);
        return updated;
    }

    /** 读取一个 Checkpoint manifest。 */
    public Optional<Checkpoint> load(String sessionId, String checkpointId) {
        return readManifest(manifestPath(sessionId, checkpointId));
    }

    /** 当前 session 的 Checkpoint 序号（已存在的最大 seq + 1）。 */
    public int nextSeq(String sessionId) {
        Path root = checkpointsRoot(sessionId);
        if (!Files.exists(root)) return 1;
        int max = 0;
        try (var stream = Files.list(root)) {
            var iter = stream.iterator();
            while (iter.hasNext()) {
                Path p = iter.next();
                String name = p.getFileName().toString();
                if (!Files.isDirectory(p)) continue;
                int dash = name.indexOf('-');
                if (dash <= 0) continue;
                try {
                    int v = Integer.parseInt(name.substring(0, dash));
                    if (v > max) max = v;
                } catch (NumberFormatException ignore) {
                    // 非数字开头 —— 跳过（理论上不会发生）
                }
            }
        } catch (IOException ex) {
            log.warn("nextSeq: list {} failed: {}", root, ex.getMessage());
        }
        return max + 1;
    }

    // ==================== 收集 / 渲染 ====================

    private List<String> collectLongTermFiles() {
        if (longTermStore == null) return List.of();
        List<String> names = new ArrayList<>();
        for (var t : longTermStore.loadTopics()) {
            names.add(t.filename());
        }
        return names;
    }

    private static Integer countMidTermRecords(SessionMessageStore store, String sessionId) {
        if (store == null) return null;
        Path mid = store.midTermPath(sessionId);
        if (!Files.exists(mid)) return 0;
        try {
            String raw = Files.readString(mid, StandardCharsets.UTF_8);
            if (raw.isBlank()) return 0;
            Object parsed = new ObjectMapper().readValue(raw, Object.class);
            if (parsed instanceof java.util.Map) {
                Object messages = ((java.util.Map<?, ?>) parsed).get("messages");
                if (messages instanceof List) return ((List<?>) messages).size();
            }
            if (parsed instanceof List) return ((List<?>) parsed).size();
        } catch (Exception ignore) {
        }
        return null;
    }

    private java.util.Map<String, Object> dagGraphJson(DagGraph g) {
        if (g == null) return new java.util.LinkedHashMap<>();
        try {
            // 用 mapper 直接序列化 — 反序列化时仍是 Map,够 manifest.json 用
            return mapper.readValue(mapper.writeValueAsString(g), new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("dagGraphJson failed: {}", ex.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    private java.util.Map<String, Object> dagStateJson(DagState s) {
        if (s == null) return new java.util.LinkedHashMap<>();
        try {
            return mapper.readValue(mapper.writeValueAsString(s), new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("dagStateJson failed: {}", ex.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    // ==================== 文件 I/O ====================

    public Path checkpointsRoot(String sessionId) {
        return sessionsRoot.resolve(safe(sessionId)).resolve("checkpoints");
    }

    public Path checkpointDir(String sessionId, String checkpointId) {
        return checkpointsRoot(sessionId).resolve(checkpointId);
    }

    public Path manifestPath(String sessionId, String checkpointId) {
        return checkpointDir(sessionId, checkpointId).resolve("manifest.json");
    }

    private Path writeManifest(Path dir, Checkpoint cp) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("manifest.json");
            Path tmp = Files.createTempFile(dir, "manifest.", ".json.tmp");
            try {
                String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cp);
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try (var ch = java.nio.channels.FileChannel.open(tmp,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException inner) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
                throw inner;
            }
            return target;
        } catch (IOException ex) {
            throw new RuntimeException("writeManifest failed for " + dir, ex);
        }
    }

    private Optional<Checkpoint> readManifest(Path manifest) {
        if (!Files.exists(manifest)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(manifest);
            return Optional.of(mapper.readValue(bytes, Checkpoint.class));
        } catch (IOException ex) {
            log.warn("readManifest failed for {}: {}", manifest, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String safe(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "default";
        return sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ==================== Records / Exceptions ====================

    public record Result(Checkpoint checkpoint, Path manifestPath) { }

    /** git 不可用（无 git / 非仓库 / 命令失败）。 */
    public static class GitUnavailableException extends RuntimeException {
        public GitUnavailableException(String message) { super(message); }
        public GitUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** 测试辅助：暴露 mapper。 */
    public ObjectMapper mapper() { return mapper; }

    /** 测试辅助：从已知对象构造 DagGraphJson（替代读 plan.json）。 */
    public static java.util.Map<String, Object> safeMapOf(Object... kvs) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put(String.valueOf(kvs[i]), kvs[i + 1]);
        }
        return m;
    }
}

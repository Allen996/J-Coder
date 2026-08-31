package org.example.agent.core.task.dag;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * DAG 状态落盘（阶段 2 引入）。
 *
 * <p>按 sessionId 组织（M.1）：
 * <pre>
 *   .agent/sessions/{sessionId}/
 *     plan.json          # 初始 DAG 静态结构（DagGraph）
 *     dag-state.json     # 运行时状态（DagState）—— 主 loop 每轮批量写
 * </pre>
 *
 * <p>写盘策略：tmp + fsync + rename,与现有 SessionMessageStore / TaskPlanRepository 一致。
 */
@Slf4j
@Component
public class DagStateRepository {

    private final Path sessionsRoot;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public DagStateRepository(@Value("${cli.project-root:}") String projectRootConfig) {
        Path root = projectRootConfig == null || projectRootConfig.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(projectRootConfig);
        this.sessionsRoot = root.toAbsolutePath().normalize().resolve(".agent").resolve("sessions");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 测试入口：自定义根目录。 */
    public DagStateRepository(Path sessionsRoot) {
        this.sessionsRoot = sessionsRoot;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(sessionsRoot);
        } catch (IOException ex) {
            log.warn("DagStateRepository: mkdir {} failed: {}", sessionsRoot, ex.getMessage());
        }
    }

    public Path sessionsRoot() {
        return sessionsRoot;
    }

    public Path sessionDir(String sessionId) {
        return sessionsRoot.resolve(safe(sessionId));
    }

    public Path planJsonPath(String sessionId) {
        return sessionDir(sessionId).resolve("plan.json");
    }

    public Path dagStatePath(String sessionId) {
        return sessionDir(sessionId).resolve("dag-state.json");
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ==================== plan.json ====================

    public DagGraph savePlan(DagGraph graph) {
        Path target = planJsonPath(graph.getSessionId());
        try {
            Files.createDirectories(target.getParent());
            atomicWriteJson(target, graph);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to save plan for session=" + graph.getSessionId(), ex);
        }
        return graph;
    }

    public Optional<DagGraph> loadPlan(String sessionId) {
        Path p = planJsonPath(sessionId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Optional.of(mapper.readValue(bytes, DagGraph.class));
        } catch (IOException ex) {
            log.warn("loadPlan failed for session={}: {}", sessionId, ex.getMessage());
            return Optional.empty();
        }
    }

    // ==================== dag-state.json ====================

    public DagState saveDagState(DagState state) {
        Path target = dagStatePath(state.getSessionId());
        try {
            Files.createDirectories(target.getParent());
            atomicWriteJson(target, state);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to save dag-state for session=" + state.getSessionId(), ex);
        }
        return state;
    }

    public Optional<DagState> loadDagState(String sessionId) {
        Path p = dagStatePath(sessionId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Optional.of(mapper.readValue(bytes, DagState.class));
        } catch (IOException ex) {
            log.warn("loadDagState failed for session={}: {}", sessionId, ex.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 内部 ====================

    private void atomicWriteJson(Path target, Object value) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try (var ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
            }
            throw ex;
        }
    }

    private static String safe(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "default";
        // 防止 ../ 等路径穿越
        return sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
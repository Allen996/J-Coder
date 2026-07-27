package org.example.agent.core.task.persistence;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.core.task.PlanEdge;
import org.example.agent.core.task.SubTask;
import org.example.agent.core.task.TaskPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * TaskPlan 持久化（part5 §8.5）。
 *
 * <p>存储结构：
 * <pre>
 *   .agent/
 *     tasks/
 *       {planId}/
 *         plan.json           # 计划总览
 *         {taskId}.json       # 每个 SubTask 一个文件
 *         verify.log          # 最后一次验证的原始输出
 * </pre>
 *
 * <p>写盘策略：临时文件 + fsync + rename（与 part2/part4 一致）。
 * 高频字段（done / currentAction / nextStep / checkpoints）走"读全文 → 反序列化 → 改字段 → 写临时 → rename"，
 * 避免锁竞争与半写文件。
 */
@Slf4j
@Component
public class TaskPlanRepository {

    private final Path tasksRoot;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskPlanRepository(@Value("${cli.project-root:}") String projectRootConfig) {
        Path root = projectRootConfig == null || projectRootConfig.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(projectRootConfig);
        this.tasksRoot = root.toAbsolutePath().normalize().resolve(".agent").resolve("tasks");

        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 测试入口：自定义 tasks 根目录。 */
    public TaskPlanRepository(Path tasksRoot) {
        this.tasksRoot = tasksRoot;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(tasksRoot);
        } catch (IOException ex) {
            log.warn("Failed to create tasks root {}: {}", tasksRoot, ex.getMessage());
        }
    }

    public Path tasksRoot() {
        return tasksRoot;
    }

    public Path planDir(String planId) {
        return tasksRoot.resolve(planId);
    }

    public Path planJsonPath(String planId) {
        return planDir(planId).resolve("plan.json");
    }

    public Path subtaskJsonPath(String planId, String taskId) {
        return planDir(planId).resolve(taskId + ".json");
    }

    public Path verifyLogPath(String planId) {
        return planDir(planId).resolve("verify.log");
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ==================== plan.json ====================

    public TaskPlan savePlan(TaskPlan plan) {
        try {
            Files.createDirectories(planDir(plan.getPlanId()));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create plan dir for " + plan.getPlanId(), ex);
        }
        try {
            String json = renderJson(plan);
            atomicWrite(planJsonPath(plan.getPlanId()), json);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to save plan " + plan.getPlanId(), ex);
        }
        return plan;
    }

    public Optional<TaskPlan> loadPlan(String planId) {
        Path p = planJsonPath(planId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Optional.of(mapper.readValue(bytes, TaskPlan.class));
        } catch (IOException ex) {
            log.warn("Failed to load plan {}: {}", planId, ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean deletePlan(String planId) {
        Path dir = planDir(planId);
        if (!Files.exists(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            log.warn("delete failed {}: {}", path, ex.getMessage());
                        }
                    });
            return true;
        } catch (IOException ex) {
            log.warn("delete plan {} failed: {}", planId, ex.getMessage());
            return false;
        }
    }

    // ==================== {taskId}.json ====================

    public SubTask saveSubTask(SubTask subTask) {
        Path p = subtaskJsonPath(subTask.getPlanId(), subTask.getTaskId());
        try {
            Files.createDirectories(p.getParent());
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create plan dir", ex);
        }
        try {
            String json = renderJson(subTask);
            atomicWrite(p, json);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to save subtask " + subTask.getTaskId(), ex);
        }
        return subTask;
    }

    public Optional<SubTask> loadSubTask(String planId, String taskId) {
        Path p = subtaskJsonPath(planId, taskId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(p);
            return Optional.of(mapper.readValue(bytes, SubTask.class));
        } catch (IOException ex) {
            log.warn("Failed to load subtask {}/{}: {}", planId, taskId, ex.getMessage());
            return Optional.empty();
        }
    }

    public List<SubTask> loadAllSubTasks(String planId) {
        Path dir = planDir(planId);
        if (!Files.exists(dir)) return List.of();
        List<SubTask> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json")
                            && !p.getFileName().toString().equals("plan.json"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        String taskId = name.substring(0, name.length() - ".json".length());
                        loadSubTask(planId, taskId).ifPresent(result::add);
                    });
        } catch (IOException ex) {
            log.warn("list subtasks for {} failed: {}", planId, ex.getMessage());
        }
        return result;
    }

    // ==================== verify.log ====================

    public Path appendVerifyLog(String planId, String chunk) {
        Path p = verifyLogPath(planId);
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, chunk == null ? "" : chunk,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            return p;
        } catch (IOException ex) {
            throw new RuntimeException("Failed to write verify.log for " + planId, ex);
        }
    }

    public Optional<String> readVerifyLog(String planId) {
        Path p = verifyLogPath(planId);
        if (!Files.exists(p)) return Optional.empty();
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.warn("Failed to read verify.log for {}: {}", planId, ex.getMessage());
            return Optional.empty();
        }
    }

    // ==================== 扫描 ====================

    public List<TaskPlan> listAllPlans() {
        if (!Files.exists(tasksRoot)) return List.of();
        List<TaskPlan> plans = new ArrayList<>();
        try (Stream<Path> stream = Files.list(tasksRoot)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> {
                        Path plan = dir.resolve("plan.json");
                        if (Files.exists(plan)) {
                            try {
                                TaskPlan tp = mapper.readValue(plan.toFile(), TaskPlan.class);
                                plans.add(tp);
                            } catch (IOException ex) {
                                log.warn("skip malformed plan at {}: {}", plan, ex.getMessage());
                            }
                        }
                    });
        } catch (IOException ex) {
            log.warn("list plans failed: {}", ex.getMessage());
        }
        return plans;
    }

    // ==================== 内部 ====================

    private String renderJson(Object value) throws IOException {
        try (var sw = new java.io.StringWriter();
             JsonGenerator gen = mapper.getFactory().createGenerator(sw)) {
            gen.useDefaultPrettyPrinter();
            mapper.writeValue(gen, value);
            return sw.toString();
        }
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            // fsync 临时文件
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

    /** 暴露给 orchestrator 用于快速校验同 plan 内 taskId 唯一性。 */
    public boolean taskIdExists(String planId, String taskId) {
        return Files.exists(subtaskJsonPath(planId, taskId));
    }

    /** 校验 plan 内 edges 引用的 taskId 都已声明 —— 用于 create_plan 的二次校验。 */
    public boolean validateEdges(TaskPlan plan) {
        if (plan.getEdges() == null) return true;
        for (PlanEdge edge : plan.getEdges()) {
            if (!plan.getSubtaskIds().contains(edge.from())
                    || !plan.getSubtaskIds().contains(edge.to())) {
                return false;
            }
        }
        return true;
    }
}
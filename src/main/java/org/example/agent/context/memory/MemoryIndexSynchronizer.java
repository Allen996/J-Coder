package org.example.agent.context.memory;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.core.task.persistence.TaskPlanRepository;
import org.example.agent.core.task.TaskPlanStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记忆文件创建时同步 {@link MemoryIndex}（part4.md §7.2 "MEMORY.md 维护"）。
 *
 * <p>四类记忆文件对应不同的索引行：
 * <ul>
 *   <li>{@code .agent/sessions/{id}/short-term.md} → "session {id} 短期对话流"</li>
 *   <li>{@code .agent/sessions/{id}/mid-term.md}   → "session {id} 中期摘要"</li>
 *   <li>{@code Nico.md}                              → "项目骨架"</li>
 *   <li>{@code .agent/tasks/{planId}/plan.json}     → "plan {planId} 任务计划"（part5 §8.5）</li>
 * </ul>
 *
 * <p>使用方式：写入方（{@link LongTermMaintainer} / 每轮 mid-term 钩子 / {@code TaskOrchestrator}）调用本类的
 * {@link #noteShortTermSession(String)} 等方法；本类负责幂等地写入 MEMORY.md。
 *
 * <p>启动时执行 {@link #syncKnownPaths()}，把磁盘上已存在的文件补登记一遍，
 * 保证冷启动后 MEMORY.md 不会丢条目（part4 §7.5）。
 */
@Slf4j
@Component
public class MemoryIndexSynchronizer {

    private final MemoryIndex memoryIndex;
    private final LongTermStore longTermStore;
    private final MidTermStore midTermStore;
    private final SessionMessageStore sessionStore;
    private final TaskPlanRepository taskPlanRepository;
    private final String projectRoot;

    public MemoryIndexSynchronizer(MemoryIndex memoryIndex,
                                   LongTermStore longTermStore,
                                   MidTermStore midTermStore,
                                   SessionMessageStore sessionStore,
                                   TaskPlanRepository taskPlanRepository) {
        this.memoryIndex = memoryIndex;
        this.longTermStore = longTermStore;
        this.midTermStore = midTermStore;
        this.sessionStore = sessionStore;
        this.taskPlanRepository = taskPlanRepository;
        this.projectRoot = "";
    }

    @PostConstruct
    public void syncKnownPaths() {
        try {
            noteLongTermFile();
            // 扫描磁盘上已有的 mid-term / short-term
            Path sessionsRoot;
            if (projectRoot.isBlank()) {
                Path probe = midTermStore.pathFor("__probe__");
                sessionsRoot = probe == null ? null : probe.getParent().getParent();
            } else {
                sessionsRoot = java.nio.file.Paths.get(projectRoot, ".agent", "sessions");
            }
            if (sessionsRoot != null && Files.exists(sessionsRoot)) {
                try (var stream = Files.list(sessionsRoot)) {
                    stream.filter(Files::isDirectory).forEach(dir -> {
                        String sessionId = dir.getFileName().toString();
                        Path mt = dir.resolve("mid-term.md");
                        Path st = dir.resolve("short-term.md");
                        if (Files.exists(mt)) noteMidTermSession(sessionId);
                        if (Files.exists(st)) noteShortTermSession(sessionId);
                    });
                }
            }
            // 扫描磁盘上已有的 task plan（part5 §8.5）
            scanAndNoteExistingPlans();
        } catch (Exception ex) {
            log.warn("MemoryIndexSynchronizer.syncKnownPaths failed: {}", ex.getMessage());
        }
    }

    private void scanAndNoteExistingPlans() {
        Path tasksRoot = taskPlanRepository.tasksRoot();
        if (tasksRoot == null || !Files.exists(tasksRoot)) return;
        try (var stream = Files.list(tasksRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String planId = dir.getFileName().toString();
                Path planFile = dir.resolve("plan.json");
                if (Files.exists(planFile)) {
                    // 只索引 ACTIVE 状态的 plan；COMPLETED/ABANDONED 的不占索引位
                    taskPlanRepository.loadPlan(planId).ifPresent(plan -> {
                        if (plan.getStatus() == TaskPlanStatus.ACTIVE) {
                            notePlan(planId);
                        }
                    });
                }
            });
        } catch (Exception ex) {
            log.warn("scanAndNoteExistingPlans failed: {}", ex.getMessage());
        }
    }

    public void noteShortTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "short-term.md");
        memoryIndex.add(rel, "session " + sessionId + " 短期对话流");
    }

    public void noteMidTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "mid-term.md");
        memoryIndex.add(rel, "session " + sessionId + " 中期摘要");
    }

    public void noteLongTermFile() {
        memoryIndex.add("Nico.md", "项目骨架");
    }

    /**
     * 把 plan.json 路径加入 MEMORY.md（part5 §8.5 "MEMORY.md 增加任务索引条目"）。
     *
     * <p>由 {@code TaskOrchestrator.createPlan} 调用；幂等，路径重复不写入多条。
     */
    public void notePlan(String planId) {
        if (planId == null || planId.isBlank()) return;
        String rel = relativePlanPath(planId);
        memoryIndex.add(rel, "plan " + planId + " 任务计划");
    }

    /**
     * 从 MEMORY.md 移除 plan 条目（plan 完成或 abandoned 时调用，part5 §8.5 "完成 → 不再占索引位"）。
     */
    public void removePlan(String planId) {
        if (planId == null || planId.isBlank()) return;
        memoryIndex.remove(relativePlanPath(planId));
    }

    private String relativePlanPath(String planId) {
        return ".agent/tasks/" + planId + "/plan.json";
    }

    private String relativeSessionPath(String sessionId, String fileName) {
        return ".agent/sessions/" + sessionId + "/" + fileName;
    }
}

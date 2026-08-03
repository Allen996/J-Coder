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
 * 记忆文件创建时同步 {@link MemoryIndex}（part4.md §7.2 "MEMORY.md 维护" / §7.4 索引定位调整）。
 *
 * <p>本类只负责索引目录页同步，<b>不</b>承担匹配逻辑。
 * 实际评分由 {@link MemoryRecallScorer} 读取各记忆文件自身的 frontmatter / JSON 头部完成。
 *
 * <p>新版文件路径（part4 §7.8）：
 * <ul>
 *   <li>{@code .agent/sessions/{id}/short-term.json} → "session {id} 短期对话流"</li>
 *   <li>{@code .agent/sessions/{id}/mid-term.json}   → "session {id} 中期摘要"</li>
 *   <li>{@code {projectRoot}/NNN-<topic>.md}        → "topic 摘要"（长期记忆按主题拆分）</li>
 *   <li>{@code .agent/tasks/{planId}/plan.json}     → "plan {planId} 任务计划"（part5 §8.5）</li>
 * </ul>
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
            noteLongTermTopics();
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
                        Path mt = dir.resolve("mid-term.json");
                        Path st = dir.resolve("short-term.json");
                        Path mtOld = dir.resolve("mid-term.md");
                        Path stOld = dir.resolve("short-term.md");
                        if (Files.exists(mt) || Files.exists(mtOld)) noteMidTermSession(sessionId);
                        if (Files.exists(st) || Files.exists(stOld)) noteShortTermSession(sessionId);
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
        String rel = relativeSessionPath(sessionId, "short-term.json");
        memoryIndex.add(rel, "session " + sessionId + " 短期对话流");
    }

    public void noteMidTermSession(String sessionId) {
        String rel = relativeSessionPath(sessionId, "mid-term.json");
        memoryIndex.add(rel, "session " + sessionId + " 中期摘要");
    }

    /** 扫描项目根目录下的所有 NNN-*.md 长期记忆文件并加入索引。 */
    public void noteLongTermTopics() {
        if (longTermStore == null) return;
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            String filename = t.filename();
            memoryIndex.add(filename, t.getSummary().isEmpty() ? "长期记忆 " + t.getSlug() : t.getSummary());
        }
    }

    /** 把 plan.json 路径加入 MEMORY.md。 */
    public void notePlan(String planId) {
        if (planId == null || planId.isBlank()) return;
        String rel = relativePlanPath(planId);
        memoryIndex.add(rel, "plan " + planId + " 任务计划");
    }

    /** 从 MEMORY.md 移除 plan 条目。 */
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

    // ============ 给 LongTermMaintainer 用的快照接口（part4 §7.6 提示词 existingTopics/existingTitles）=============

    public java.util.List<String> snapshotLongTermTopicSlugs() {
        if (longTermStore == null) return java.util.List.of();
        java.util.List<String> slugs = new java.util.ArrayList<>();
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            slugs.add(t.getSeq() + "-" + t.getSlug());
        }
        return slugs;
    }

    public java.util.List<String> snapshotLongTermEntryTitles() {
        if (longTermStore == null) return java.util.List.of();
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (LongTermStore.Topic t : longTermStore.loadTopics()) {
            for (LongTermStore.Entry e : t.getEntries()) {
                if (!e.getContent().isBlank()) titles.add(e.getContent());
            }
        }
        return titles;
    }
}
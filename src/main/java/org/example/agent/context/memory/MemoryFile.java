package org.example.agent.context.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆文件通用操作（part4.md §7.4 / §7.5）。
 *
 * <p>所有记忆文件统一 Markdown 格式：头部 YAML frontmatter 记录元数据，正文 Markdown 内容。
 *
 * <p>一致性保证：
 * <ul>
 *   <li>整文件覆写：写临时文件 + rename，避免半写损坏。</li>
 *   <li>append-only 增量：直接追加写，依赖 OS 单 write 原子性。</li>
 *   <li>启动时扫描磁盘重建内存索引；解析失败自动备份为损坏文件并跳过。</li>
 * </ul>
 */
public final class MemoryFile {

    private static final Logger log = LoggerFactory.getLogger(MemoryFile.class);

    private MemoryFile() { }

    /** 整文件写入：render frontmatter + body → tmp → rename → fsync。 */
    public static void writeAtomic(Path target, Map<String, Object> frontmatter, String body) {
        if (target == null) return;
        Path parent = target.getParent();
        try {
            if (parent != null) Files.createDirectories(parent);
            Path tmp = parent == null
                    ? Files.createTempFile(target.getFileName().toString() + ".tmp.", ".md")
                    : Files.createTempFile(parent, target.getFileName().toString() + ".tmp.", ".md");
            Map<String, Object> fm = new LinkedHashMap<>(Frontmatter.defaults());
            if (frontmatter != null) fm.putAll(frontmatter);
            String full = Frontmatter.render(fm) + "\n" + body;
            Files.writeString(tmp, full, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                Files.move(tmp, target,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.setLastModifiedTime(target, FileTime.from(java.time.Instant.now()));
            } catch (Exception ignore) { }
        } catch (IOException ex) {
            log.warn("MemoryFile.writeAtomic failed for {}: {}", target, ex.getMessage());
        }
    }

    /** append-only 增量：把段落追加到正文末尾，不动 frontmatter。 */
    public static void appendSection(Path target, String section) {
        if (target == null || section == null || section.isEmpty()) return;
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(target, section, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("MemoryFile.appendSection failed for {}: {}", target, ex.getMessage());
        }
    }

    /** 读全文；缺失时返回空串。解析失败时备份为损坏文件并返回空串。 */
    public static String readOrBackup(Path target) {
        if (target == null || !Files.exists(target)) return "";
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("MemoryFile.readOrBackup failed for {}: {}", target, ex.getMessage());
            backupCorrupted(target);
            return "";
        }
    }

    private static void backupCorrupted(Path target) {
        try {
            Path backup = target.resolveSibling(target.getFileName().toString() + ".corrupted-" + System.currentTimeMillis());
            Files.move(target, backup);
        } catch (IOException ignore) { }
    }
}

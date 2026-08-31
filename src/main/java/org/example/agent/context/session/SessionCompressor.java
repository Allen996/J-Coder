package org.example.agent.context.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * SubAgent session 压缩器（阶段 3 引入）。
 *
 * <p>SubAgent 跑完后,把 short-term.json 的所有消息折叠进 mid-term.json,然后删除 short-term.json。
 *
 * <p>设计要点：
 * <ul>
 *   <li>幂等性 —— short-term.json 不存在时直接跳过(K.2)</li>
 *   <li>写盘顺序 —— 先 read 旧 mid-term + read short-term,合并后写 mid-term.tmp + rename,
 *       再删 short-term.json。任何一步失败,旧文件不变(K.4)</li>
 *   <li>压缩元信息 —— mid-term 首条记录为 {@code [meta] subagent_compress} 元事件,
 *       表明这是 SubAgent session 的折叠(区别于普通 auto-compress)</li>
 *   <li>worklog 不动 —— short-term.json 在内存已经全量在 Session.messages,
 *       worklog 是磁盘上的 append-only 日志,压缩不动它</li>
 * </ul>
 *
 * <p>调用方在 SubAgentRunner 完成后调 {@link #compressIfPresent(Path, ObjectMapper)},
 * short-term 路径来自 {@code SessionMessageStore.shortTermPath(taskId)}。
 */
@Slf4j
@Component
public class SessionCompressor {

    /** 压缩元事件标签 —— 用于 mid-term.json 首条 [meta] 记录。 */
    public static final String META_TAG_SUBAGENT_COMPRESS = "subagent_compress";

    /**
     * 把 short-term.json 折叠进 mid-term.json,然后删除 short-term.json。
     * 如果 short-term.json 不存在,noop(幂等性)。
     *
     * @param sessionDir SubAgent session 目录(例如 .agent/sessions/st-3/)
     * @param mapper 复用 SessionMessageStore 的 ObjectMapper(避免重复创建)
     * @return 是否执行了压缩(false 表示 short-term 不存在)
     */
    public boolean compressIfPresent(Path sessionDir, ObjectMapper mapper) {
        if (sessionDir == null || mapper == null) return false;
        Path shortTerm = sessionDir.resolve("short-term.json");
        Path midTerm = sessionDir.resolve("mid-term.json");
        if (!Files.exists(shortTerm)) {
            log.debug("compressIfPresent: short-term.json missing at {}; skip", shortTerm);
            return false;
        }
        try {
            Files.createDirectories(sessionDir);

            // 1) 读 short-term 全量记录(原始 JSON 数组)
            List<Object> shortRecords = readRecords(shortTerm, mapper);

            // 2) 读 mid-term 全量记录(如有)
            List<Object> midRecords = Files.exists(midTerm)
                    ? readRecords(midTerm, mapper)
                    : new ArrayList<>();

            // 3) 构造 [meta] 元事件作为 mid-term 首条
            Object metaRecord = buildMetaRecord(META_TAG_SUBAGENT_COMPRESS,
                    shortRecords.size(), sessionDir, mapper);

            // 4) 合并:meta + midRecords + shortRecords
            List<Object> merged = new ArrayList<>(midRecords.size() + shortRecords.size() + 1);
            merged.add(metaRecord);
            merged.addAll(midRecords);
            merged.addAll(shortRecords);

            // 5) 原子写 mid-term.tmp → rename(K.4)
            writeAtomic(midTerm, merged, mapper);

            // 6) 删 short-term.json
            Files.deleteIfExists(shortTerm);

            log.info("compressIfPresent: session={} compressed {} short-term records into mid-term ({} total)",
                    sessionDir.getFileName(), shortRecords.size(), merged.size());
            return true;
        } catch (IOException ex) {
            log.warn("compressIfPresent: failed for {}: {}", sessionDir, ex.getMessage());
            return false;
        }
    }

    private List<Object> readRecords(Path path, ObjectMapper mapper) throws IOException {
        if (!Files.exists(path)) return new ArrayList<>();
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (raw.isBlank()) return new ArrayList<>();
        Object parsed = mapper.readValue(raw, Object.class);
        if (parsed instanceof java.util.Map) {
            // MemoryFile envelope: { schema, kind, sessionId, ..., messages: [...] }
            Object messages = ((java.util.Map<?, ?>) parsed).get("messages");
            if (messages instanceof List) return new ArrayList<>((List<?>) messages);
        }
        if (parsed instanceof List) {
            return new ArrayList<>((List<?>) parsed);
        }
        log.warn("readRecords: unexpected root type {} at {}", parsed == null ? "null" : parsed.getClass(), path);
        return new ArrayList<>();
    }

    private Object buildMetaRecord(String tag, int foldedCount, Path sessionDir, ObjectMapper mapper) {
        java.util.LinkedHashMap<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("role", "meta");
        meta.put("tag", tag);
        meta.put("foldedRecords", foldedCount);
        meta.put("session", sessionDir.getFileName().toString());
        meta.put("ts", java.time.Instant.now().toString());
        return meta;
    }

    private void writeAtomic(Path target, List<Object> records, ObjectMapper mapper) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), records);
            try (var ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
            throw ex;
        }
    }
}
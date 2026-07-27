package org.example.agent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.List;

/**
 * SubTask 内的进度快照（part5 §8.6）。
 *
 * <p>职责边界：只存最小恢复元信息（files / functions / note），不存文件内容、不存 diff。
 * 真正当前状态以磁盘上文件的现状为准 —— 恢复时 LLM 需要 read_file 确认。
 *
 * <p>两类 checkpoint：
 * <ul>
 *   <li>自动 checkpoint：仅填 files + note（基于工具名兜底），functions 留空</li>
 *   <li>LLM 主动 checkpoint：save_checkpoint 工具调用，可填 functions 与更精确的 note</li>
 * </ul>
 */
@Getter
@Builder
@ToString
public final class Checkpoint {

    @JsonProperty("checkpointId")
    private final String checkpointId;

    @JsonProperty("createdAt")
    private final Instant createdAt;

    @JsonProperty("files")
    private final List<String> files;

    @JsonProperty("functions")
    private final List<String> functions;

    @JsonProperty("note")
    private final String note;

    @JsonProperty("automatic")
    private final boolean automatic;

    @JsonCreator
    public Checkpoint(
            @JsonProperty("checkpointId") String checkpointId,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("files") List<String> files,
            @JsonProperty("functions") List<String> functions,
            @JsonProperty("note") String note,
            @JsonProperty("automatic") boolean automatic) {
        this.checkpointId = checkpointId == null ? "" : checkpointId;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.files = files == null ? List.of() : List.copyOf(files);
        this.functions = functions == null ? List.of() : List.copyOf(functions);
        this.note = note == null ? "" : note;
        this.automatic = automatic;
    }

    public static Checkpoint automatic(String id, List<String> files, String note) {
        return new Checkpoint(id, Instant.now(), files, null, note, true);
    }

    public static Checkpoint manual(String id, List<String> files, List<String> functions, String note) {
        return new Checkpoint(id, Instant.now(), files, functions, note, false);
    }
}

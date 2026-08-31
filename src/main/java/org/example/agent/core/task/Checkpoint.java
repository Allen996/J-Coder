package org.example.agent.core.task;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主 Agent 决策暂停点的完整状态快照（阶段 4 重写）。
 *
 * <p>触发时机：
 * <ul>
 *   <li>主 Agent 主动调 {@code checkpoint} 工具（识别到两难决策点）</li>
 *   <li>用户手动 {@code /checkpoint} 命令</li>
 * </ul>
 *
 * <p>落盘位置：{@code .agent/sessions/{sessionId}/checkpoints/{seq}-{reason}/manifest.json}。
 * 编号方式 {@code seq} 按 session 内顺序递增；{@code reason} 是触发原因简写。
 *
 * <p>包含字段：
 * <ul>
 *   <li>主 Agent 的完整 system prompt（生成期快照）</li>
 *   <li>工具注册表 snapshot（哪些 @Tool 可调用）</li>
 *   <li>DAG 状态（节点 + state + lastResult）</li>
 *   <li>主 worklog tail seq / mid-term seq（用于一致性校验）</li>
 *   <li>long-term 主题文件清单（seq + filename）</li>
 *   <li>git commit hash（{@code git rev-parse HEAD}）—— 不可用时拒绝写入</li>
 *   <li>触发原因 / 待决策问题 / 相关制品</li>
 *   <li>状态：{@link #PENDING_DECISION} / {@link #DECIDED} / {@link #ABANDONED}</li>
 * </ul>
 */
@Getter
@Builder
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Checkpoint {

    /** 触发后等用户拍板。 */
    public static final String STATUS_PENDING_DECISION = "PENDING_DECISION";
    /** 用户已给出决策。 */
    public static final String STATUS_DECIDED = "DECIDED";
    /** 主动放弃（用户中断或主 Agent 改主意）。 */
    public static final String STATUS_ABANDONED = "ABANDONED";

    @JsonProperty("checkpointId")
    private final String checkpointId;

    @JsonProperty("seq")
    private final int seq;

    @JsonProperty("sessionId")
    private final String sessionId;

    @JsonProperty("reason")
    private final String reason;

    @JsonProperty("decisionQuestion")
    private final String decisionQuestion;

    @JsonProperty("relevantArtifacts")
    private final List<String> relevantArtifacts;

    @JsonProperty("status")
    private final String status;

    @JsonProperty("createdAt")
    private final Instant createdAt;

    @JsonProperty("decidedAt")
    private final Instant decidedAt;

    @JsonProperty("decidedBy")
    private final String decidedBy;

    @JsonProperty("decision")
    private final String decision;

    @JsonProperty("gitCommit")
    private final String gitCommit;

    @JsonProperty("systemPrompt")
    private final String systemPrompt;

    @JsonProperty("toolRegistry")
    private final List<String> toolRegistry;

    /** DAG 静态图（plan.json 同样的序列化）。 */
    @JsonProperty("dagGraph")
    private final Map<String, Object> dagGraph;

    /** DAG 运行时状态（dag-state.json 同样的序列化）。 */
    @JsonProperty("dagState")
    private final Map<String, Object> dagState;

    @JsonProperty("worklogTailSeq")
    private final Integer worklogTailSeq;

    @JsonProperty("midTermSeq")
    private final Integer midTermSeq;

    @JsonProperty("longTermFiles")
    private final List<String> longTermFiles;

    @JsonProperty("parentCheckpointId")
    private final String parentCheckpointId;

    @JsonProperty("planId")
    private final String planId;

    @JsonCreator
    public Checkpoint(
            @JsonProperty("checkpointId") String checkpointId,
            @JsonProperty("seq") int seq,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("reason") String reason,
            @JsonProperty("decisionQuestion") String decisionQuestion,
            @JsonProperty("relevantArtifacts") List<String> relevantArtifacts,
            @JsonProperty("status") String status,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("decidedAt") Instant decidedAt,
            @JsonProperty("decidedBy") String decidedBy,
            @JsonProperty("decision") String decision,
            @JsonProperty("gitCommit") String gitCommit,
            @JsonProperty("systemPrompt") String systemPrompt,
            @JsonProperty("toolRegistry") List<String> toolRegistry,
            @JsonProperty("dagGraph") Map<String, Object> dagGraph,
            @JsonProperty("dagState") Map<String, Object> dagState,
            @JsonProperty("worklogTailSeq") Integer worklogTailSeq,
            @JsonProperty("midTermSeq") Integer midTermSeq,
            @JsonProperty("longTermFiles") List<String> longTermFiles,
            @JsonProperty("parentCheckpointId") String parentCheckpointId,
            @JsonProperty("planId") String planId) {
        this.checkpointId = checkpointId == null ? "" : checkpointId;
        this.seq = Math.max(0, seq);
        this.sessionId = sessionId == null ? "" : sessionId;
        this.reason = reason == null ? "" : reason;
        this.decisionQuestion = decisionQuestion == null ? "" : decisionQuestion;
        this.relevantArtifacts = relevantArtifacts == null ? List.of() : List.copyOf(relevantArtifacts);
        this.status = status == null ? STATUS_PENDING_DECISION : status;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.decidedAt = decidedAt;
        this.decidedBy = decidedBy == null ? "" : decidedBy;
        this.decision = decision == null ? "" : decision;
        this.gitCommit = gitCommit == null ? "" : gitCommit;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.toolRegistry = toolRegistry == null ? List.of() : List.copyOf(toolRegistry);
        this.dagGraph = dagGraph == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dagGraph);
        this.dagState = dagState == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dagState);
        this.worklogTailSeq = worklogTailSeq;
        this.midTermSeq = midTermSeq;
        this.longTermFiles = longTermFiles == null ? List.of() : List.copyOf(longTermFiles);
        this.parentCheckpointId = parentCheckpointId == null ? "" : parentCheckpointId;
        this.planId = planId == null ? "" : planId;
    }

    /** 用于生成决策快照 + 决策内容叠加。 */
    public Checkpoint withDecision(String decisionText, String decidedBy) {
        return new Checkpoint(
                checkpointId, seq, sessionId, reason, decisionQuestion, relevantArtifacts,
                STATUS_DECIDED,
                createdAt, Instant.now(),
                decidedBy == null ? "" : decidedBy,
                decisionText == null ? "" : decisionText,
                gitCommit, systemPrompt, toolRegistry, dagGraph, dagState,
                worklogTailSeq, midTermSeq, longTermFiles,
                parentCheckpointId, planId);
    }

    /** 标记为 ABANDONED。 */
    public Checkpoint withAbandoned(String note) {
        return new Checkpoint(
                checkpointId, seq, sessionId, reason, decisionQuestion, relevantArtifacts,
                STATUS_ABANDONED,
                createdAt, Instant.now(),
                "user",
                note == null ? "" : note,
                gitCommit, systemPrompt, toolRegistry, dagGraph, dagState,
                worklogTailSeq, midTermSeq, longTermFiles,
                parentCheckpointId, planId);
    }

    /** 安全地把 reason 转成文件系统安全的 slug（替换非法字符 + 截断）。 */
    public static String slugifyReason(String reason) {
        if (reason == null || reason.isBlank()) return "decision";
        String s = reason.trim().toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (s.isEmpty()) return "decision";
        if (s.length() > 48) s = s.substring(0, 48);
        return s;
    }

    /**
     * 构造一个"PENDING_DECISION"状态的新 Checkpoint。
     *
     * @param checkpointId       同 {seq}-{reason} 或自定义 id
     * @param seq                session 内顺序（>=1）
     * @param sessionId          主 session id
     * @param reason             触发原因短述（也是目录 slug 的一部分）
     * @param decisionQuestion   待用户决策的问题
     * @param relevantArtifacts  与决策相关的文件路径列表
     * @param gitCommit          {@code git rev-parse HEAD} 的输出（必填，非空）
     * @param systemPrompt       主 Agent system prompt 快照
     * @param toolRegistry       工具注册表 snapshot（工具名列表）
     * @param dagGraphJson       DAG 静态图（toMap 后的 Map）
     * @param dagStateJson       DAG 运行时（toMap 后的 Map）
     * @param worklogTailSeq     主 worklog 末尾序号（可空）
     * @param midTermSeq         mid-term 消息条数（可空）
     * @param longTermFiles      long-term 主题文件名列表
     * @param parentCheckpointId 上一个 Checkpoint id（可空）
     * @param planId             当前 active plan id（可空）
     */
    public static Checkpoint pending(String checkpointId, int seq, String sessionId,
                                     String reason, String decisionQuestion,
                                     List<String> relevantArtifacts,
                                     String gitCommit,
                                     String systemPrompt,
                                     List<String> toolRegistry,
                                     Map<String, Object> dagGraphJson,
                                     Map<String, Object> dagStateJson,
                                     Integer worklogTailSeq,
                                     Integer midTermSeq,
                                     List<String> longTermFiles,
                                     String parentCheckpointId,
                                     String planId) {
        return new Checkpoint(
                checkpointId,
                seq,
                sessionId,
                reason,
                decisionQuestion,
                relevantArtifacts,
                STATUS_PENDING_DECISION,
                Instant.now(),
                null,
                "",
                "",
                gitCommit,
                systemPrompt,
                toolRegistry,
                dagGraphJson,
                dagStateJson,
                worklogTailSeq,
                midTermSeq,
                longTermFiles == null ? new ArrayList<>() : longTermFiles,
                parentCheckpointId,
                planId);
    }
}

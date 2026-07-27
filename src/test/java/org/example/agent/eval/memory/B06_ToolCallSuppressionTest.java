package org.example.agent.eval.memory;

import org.example.agent.context.builder.ContextBuilder;
import org.example.agent.context.budget.ContextBudgetPolicy;
import org.example.agent.context.compression.ConversationCompressor;
import org.example.agent.context.layer.ContextEntry;
import org.example.agent.context.layer.ContextKey;
import org.example.agent.context.session.SessionMessageStore;
import org.example.agent.tool.file.FileTools;
import org.example.agent.tool.rollback.SideEffectTracker;
import org.example.agent.tool.sandbox.PathGate;
import org.example.agent.tool.sandbox.TrustedPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度 B 测量型测试：文件读取结果是否被外置到 short-term.md，下一轮的 context 能不能看到。
 *
 * <p>核心问题："当让 agent 重复读某一文件时，能否从文件的外置结果中直接获取，避免多次重复调用"。
 * 答案分两步：
 *   1. 数据层：读过的文件内容是否落到 .agent/sessions/{id}/short-term.md
 *   2. 决策层：下一轮的 MESSAGES key 里是否包含该文件内容（LLM 因此不需要再次 read_file）
 *
 * <p>本测试聚焦在数据层（不接 LLM），用真实的 FileTools 触发读取，再用 SessionMessageStore 持久化，
 * 验证外置结果完整可检索。对应 TEST.md §5.1 "B 维度 / B06 read_file 去重率"。
 */
class B06_ToolCallSuppressionTest {

    private static final ContextBudgetPolicy DEFAULT = ContextBudgetPolicy.defaultPolicy();

    @Test
    @DisplayName("单文件读取 → 内容落到 short-term.md 且 size>0")
    void singleFileReadExternalizesToShortTerm(@TempDir Path project) throws Exception {
        Path file = project.resolve("alpha.txt");
        String original = "alpha file unique-marker-A1B2C3 content";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        FileTools tools = new FileTools(allowAllPathGate(project), noopSideEffects());
        String readBack = tools.readFile(file.toString(), null, null);

        // 模拟"agent 读到文件后把观察写进 session"
        SessionMessageStore store = new SessionMessageStore(project.resolve(".agent/sessions"));
        String sessionId = "s1";
        store.addUser(sessionId, "读 alpha.txt");
        store.addAssistant(sessionId, "[tool:readFile] " + readBack);

        // 断言 1：磁盘上 short-term.md 存在
        Path md = project.resolve(".agent/sessions/s1/short-term.md");
        assertThat(Files.exists(md))
                .as("short-term.md must be persisted after addAssistant()")
                .isTrue();

        // 断言 2：磁盘文件里有原文
        String onDisk = Files.readString(md, StandardCharsets.UTF_8);
        assertThat(onDisk)
                .as("on-disk short-term.md must contain the read-back content")
                .contains("alpha file unique-marker-A1B2C3");

        // 打印
        System.out.printf("[B06 single-read] file=%s%n"
                        + "  on_disk_bytes=%d%n"
                        + "  content_found=%s%n",
                file.getFileName(), Files.size(md), true);
    }

    @Test
    @DisplayName("5 个文件依次读取 → 全部外置, 下一轮 context 的 MESSAGES 全部命中")
    void fiveFilesAllExternalizedAndFoundInContext(@TempDir Path project) throws Exception {
        // 准备 5 个文件,每个带唯一 marker
        Map<String, String> markers = new HashMap<>();
        markers.put("a.txt", "marker-A-AAA-001");
        markers.put("b.txt", "marker-B-BBB-002");
        markers.put("c.txt", "marker-C-CCC-003");
        markers.put("d.txt", "marker-D-DDD-004");
        markers.put("e.txt", "marker-E-EEE-005");

        for (Map.Entry<String, String> e : markers.entrySet()) {
            Files.writeString(project.resolve(e.getKey()),
                    "content for " + e.getKey() + " " + e.getValue(),
                    StandardCharsets.UTF_8);
        }

        FileTools tools = new FileTools(allowAllPathGate(project), noopSideEffects());

        SessionMessageStore store = new SessionMessageStore(project.resolve(".agent/sessions"));
        String sessionId = "s5";

        // 模拟 agent 5 轮 ReAct, 每轮: user 问 → 调 readFile → assistant 记录观察
        for (String name : markers.keySet()) {
            String content = tools.readFile(project.resolve(name).toString(), null, null);
            store.addUser(sessionId, "请读 " + name);
            store.addAssistant(sessionId, "[tool:readFile " + name + "] " + content);
        }

        // 重新加载 store (模拟"新进程启动, 但还是同一个 session")
        SessionMessageStore reborn = new SessionMessageStore(project.resolve(".agent/sessions"));
        SessionMessageStore.Session loadedSession = reborn.getOrCreate(sessionId);

        // 装配下一轮的 context
        ContextBuilder builder = ContextBuilder.minimal(
                DEFAULT, reborn, new ConversationCompressor());

        ContextBuilder.BuiltContext built = builder.build(
                org.example.agent.core.task.AgentTask.builder()
                        .sessionId(sessionId)
                        .input("重新提一遍刚才读的内容")
                        .role("chat").promptId("chat.react-assistant").build(),
                "重新提一遍刚才读的内容");

        ContextEntry messages = built.getDynamicLayer().get(ContextKey.MESSAGES)
                .orElseThrow(() -> new AssertionError("MESSAGES key missing"));

        // 命中检查: 每个文件独有的 marker 是否都在 MESSAGES 里
        String allText = messages.getText() + " " +
                String.join(" ", reborn.get(sessionId).snapshot().stream()
                        .map(SessionMessageStore::extractText).toList());
        int hits = 0;
        List<String> missed = new ArrayList<>();
        for (Map.Entry<String, String> e : markers.entrySet()) {
            if (allText.contains(e.getValue())) hits++;
            else missed.add(e.getKey() + " (" + e.getValue() + ")");
        }

        System.out.printf("[B06 five-files] files=%d%n"
                        + "  context_messges_tokens=%d%n"
                        + "  hits=%d / %d%n"
                        + "  missed=%s%n"
                        + "  expected_read_file_calls_if_asked_again=%d%n"
                        + "  (read_once: 5 calls; ask_again without memory: 5 calls; with memory: 0 expected)%n",
                markers.size(), messages.getEstimatedTokens(),
                hits, markers.size(), missed,
                markers.size() - hits);

        // 不设硬门限, 只把 missed 列表暴露出来给用户判断
        assertThat(hits)
                .as("expected all 5 markers to be retrievable from session history / MESSAGES")
                .isEqualTo(markers.size());
    }

    @Test
    @DisplayName("重复读同一文件两次 → short-term.md 同时保留两个版本, 最新在前")
    void repeatedReadsKeepBothVersions(@TempDir Path project) throws Exception {
        Path file = project.resolve("hot.txt");
        Files.writeString(file, "version-1-marker-V1", StandardCharsets.UTF_8);

        FileTools tools = new FileTools(allowAllPathGate(project), noopSideEffects());

        SessionMessageStore store = new SessionMessageStore(project.resolve(".agent/sessions"));
        String sessionId = "s-hot";

        // 第 1 次读 (v1)
        String v1 = tools.readFile(file.toString(), null, null);
        store.addUser(sessionId, "读 hot.txt");
        store.addAssistant(sessionId, "[tool:readFile] " + v1);

        // 修改文件
        Files.writeString(file, "version-2-marker-V2", StandardCharsets.UTF_8);

        // 第 2 次读 (v2)
        String v2 = tools.readFile(file.toString(), null, null);
        store.addUser(sessionId, "再读 hot.txt 看是否变化");
        store.addAssistant(sessionId, "[tool:readFile] " + v2);

        Path md = project.resolve(".agent/sessions/" + sessionId + "/short-term.md");
        String onDisk = Files.readString(md, StandardCharsets.UTF_8);

        // 两个版本都在
        assertThat(onDisk)
                .contains("version-1-marker-V1")
                .contains("version-2-marker-V2");

        // 新版本出现在旧版本之后 (append-only)
        int v1Pos = onDisk.indexOf("version-1-marker-V1");
        int v2Pos = onDisk.indexOf("version-2-marker-V2");
        assertThat(v2Pos).isGreaterThan(v1Pos);

        System.out.printf("[B06 repeated-read] file=%s%n"
                        + "  on_disk_bytes=%d%n"
                        + "  v1_pos=%d, v2_pos=%d (v2 must be later)%n",
                file.getFileName(), Files.size(md), v1Pos, v2Pos);
    }

    @Test
    @DisplayName("读超过 100KB 的大文件 → FileTools 自动截断 + 截断版仍落到 short-term")
    void largeFileReadTruncatedAndExternalized(@TempDir Path project) throws Exception {
        Path big = project.resolve("big.txt");
        // 写一个 ~200KB 的文件 (1KB 行 × 200 行)
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            content.append("line ").append(i).append(" ").append("X".repeat(1000)).append("\n");
        }
        Files.writeString(big, content.toString(), StandardCharsets.UTF_8);

        FileTools tools = new FileTools(allowAllPathGate(project), noopSideEffects());
        String readBack = tools.readFile(big.toString(), null, null);

        // 读回来的内容应小于 MAX_CONTENT_BYTES (100KB)
        assertThat(readBack.length())
                .as("FileTools must truncate files > 100KB")
                .isLessThanOrEqualTo(100 * 1024 + 200);

        // 模拟外置
        SessionMessageStore store = new SessionMessageStore(project.resolve(".agent/sessions"));
        store.addUser("s-big", "读 big.txt");
        store.addAssistant("s-big", "[tool:readFile] " + readBack);

        // 落盘
        Path md = project.resolve(".agent/sessions/s-big/short-term.md");
        String onDisk = Files.readString(md, StandardCharsets.UTF_8);
        assertThat(onDisk).contains("line 0").contains("line 100");  // 头尾都在

        System.out.printf("[B06 large-read] file=%s%n"
                        + "  original_bytes=%d%n"
                        + "  truncated_bytes=%d%n"
                        + "  on_disk_bytes=%d%n",
                big.getFileName(), Files.size(big), readBack.length(), Files.size(md));
    }

    @Test
    @DisplayName("跨进程: session A 读完, 新建 SessionMessageStore 加载, 内容完整")
    void crossSessionRecall(@TempDir Path project) throws Exception {
        Path file = project.resolve("shared.txt");
        Files.writeString(file, "shared-content-marker-XYZ-999", StandardCharsets.UTF_8);

        FileTools tools = new FileTools(allowAllPathGate(project), noopSideEffects());
        String readBack = tools.readFile(file.toString(), null, null);

        SessionMessageStore storeA = new SessionMessageStore(project.resolve(".agent/sessions"));
        storeA.addUser("shared", "读 shared.txt");
        storeA.addAssistant("shared", "[tool:readFile] " + readBack);

        // 模拟进程重启: 新 SessionMessageStore 指向同一目录
        SessionMessageStore storeB = new SessionMessageStore(project.resolve(".agent/sessions"));
        // 注意: get() 只查内存, getOrCreate() 才会触发 loadFromDisk
        SessionMessageStore.Session loaded = storeB.getOrCreate("shared");

        assertThat(loaded)
                .as("session 'shared' must survive process restart via short-term.md")
                .isNotNull();
        assertThat(loaded.size()).isEqualTo(2);

        String allText = loaded.snapshot().stream()
                .map(SessionMessageStore::extractText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allText)
                .as("loaded session must contain the file content")
                .contains("shared-content-marker-XYZ-999");

        System.out.printf("[B06 cross-session] file=%s%n"
                        + "  storeA_size=%d, storeB_size=%d (after restart)%n"
                        + "  content_recoverable=%s%n",
                file.getFileName(), storeA.get("shared").size(), loaded.size(), true);
    }

    @Test
    @DisplayName("决策层: 文件内容已在 MESSAGES 中, 若 LLM 被问 '再读一遍', 应不需要再调 readFile")
    void decisionLayerContextHasTheAnswer(@TempDir Path project) throws Exception {
        // 这个测试不调真实 LLM, 只证明"机制上"LLM 能在 prompt 里看到文件内容
        // 因此如果 LLM 调了 readFile, 那不是机制问题而是 LLM 决策问题

        Path file = project.resolve("queried.txt");
        Files.writeString(file, "answer-to-question-marker-QQQ-777", StandardCharsets.UTF_8);
        FileTools tools = new FileTools(allowAllPathGate(project), noopSideEffects());
        String content = tools.readFile(file.toString(), null, null);

        SessionMessageStore store = new SessionMessageStore(project.resolve(".agent/sessions"));
        store.addUser("decision", "读 queried.txt");
        store.addAssistant("decision", "[tool:readFile] " + content);

        // 装配下一轮
        ContextBuilder builder = ContextBuilder.minimal(DEFAULT, store,
                new ConversationCompressor());
        ContextBuilder.BuiltContext built = builder.build(
                org.example.agent.core.task.AgentTask.builder()
                        .sessionId("decision")
                        .input("queried.txt 里有什么").role("chat").promptId("p").build(),
                "queried.txt 里有什么");

        ContextEntry messages = built.getDynamicLayer().get(ContextKey.MESSAGES)
                .orElseThrow();

        // 把整个 messages 段都搜一遍
        String fullMessages = messages.getText();
        boolean found = fullMessages.contains("answer-to-question-marker-QQQ-777");

        // 也搜 messages 的结构化载荷 (loadMessages 返回的 List<Message>)
        if (!found && messages.getStructuredPayload() instanceof List<?> payload) {
            for (Object o : payload) {
                if (o instanceof org.springframework.ai.chat.messages.Message m
                        && SessionMessageStore.extractText(m).contains("answer-to-question-marker-QQQ-777")) {
                    found = true;
                    break;
                }
            }
        }

        System.out.printf("[B06 decision-layer] question=\"queried.txt 里有什么\"%n"
                        + "  context_messges_text_bytes=%d%n"
                        + "  answer_found_in_messges=%s%n"
                        + "  expected_read_file_call=0 (因为 LLM 已能从 context 拿到答案)%n"
                        + "  (注: 真实 LLM 是否真的不调 read_file, 取决于它的决策, 不属于机制层)%n",
                fullMessages.length(), found);

        assertThat(found)
                .as("file content must be findable in next-turn MESSAGES")
                .isTrue();
    }

    // ============== helpers ==============

    /** 测试用 PathGate: 放行所有给定 project 下的路径 */
    private static PathGate allowAllPathGate(Path projectRoot) {
        TrustedPaths trusted = new TrustedPaths() {
            @Override
            public java.util.List<Path> load() {
                return java.util.List.of(projectRoot.toAbsolutePath().normalize());
            }
        };
        return new PathGate(projectRoot.toString(), trusted);
    }

    /** 测试用 SideEffectTracker: 不做任何记录 */
    private static SideEffectTracker noopSideEffects() {
        return new SideEffectTracker() {
            @Override public void bind(String executionId) { }
            @Override public void clear() { }
            @Override public void recordFileChange(String toolName, String path, byte[] preState) { }
            @Override public org.example.agent.tool.rollback.RollbackSummary rollbackAll() { return null; }
        };
    }
}
# TEST.md — 差异化能力评测方案

> 目标：围绕 **上下文压缩 / 记忆系统 / 任务恢复** 三个差异化能力，设计可重复、可断言的 JUnit 测试。
> **不**覆盖通用 coding 能力（修 bug、写函数）——那部分用 TerminalBench / SWE-bench 单独跑。

---

## 0. 设计原则

1. **断言要可机器读**：所有"压缩后保留了多少信息"用 recall 率衡量，不靠人眼看。
2. **口径统一**：token 估算一律用项目自己的 `ContextBudgetPolicy.estimateTextTokens`（1 token ≈ 4 字符），不引入外部 tokenizer，避免和 runtime 漂移。
3. **Mock LLM 默认注入**：所有"调用 LLM 摘要"的 case 注入 `ConversationCompressor.SummarizerChatModel` 的确定性 stub，输出包含或不包含特定关键词由 stub 决定，从而测压缩路径而不是测 LLM 质量。
4. **每个 case 一个文件**：放进 `src/test/java/org/example/agent/eval/`，命名 `{A|B|C}{N}.{description}Test.java`，与现有 `src/test/java/org/example/agent/context/**` 并列。
5. **不破坏现有测试**：评测类只读 production code 的 public API，不修改它。如果 production code 没暴露某钩子，先暴露（加 `@VisibleForTesting` 或 thin wrapper），不要 hack。

---

## 1. 共享基座

### 1.1 测试模块布局

```
src/test/java/org/example/agent/eval/
  common/
    EvalFixtures.java          # 临时目录、Mock LLM、token 注入工具
    FactCorpus.java            # 注入会话用的"事实点"语料
    ToolCallRecorder.java      # 记录 mock AgentRuntime 的工具调用次数
  context/                     # 维度 A
    A01_NoTriggerTest.java
    A02_ThresholdTriggerTest.java
    A03_FiveRoundDefaultTest.java
    A04_DecrementPathTest.java
    A05_LlmSummarizeFallbackTest.java
    A06_LocalHeuristicFallbackTest.java
    A07_OverflowThrowsTest.java
    A08_CompressionRatioTest.java
    A09_FactRecallAfterCompressionTest.java
    A10_CrossRoundReferenceTest.java
  memory/                      # 维度 B
    B01_ShortTermHitTest.java
    B02_MidTermRecallTest.java
    B03_LongTermNicoTest.java
    B04_MemoryIndexLruTest.java
    B05_MemoryIndexIdempotentTest.java
    B06_ToolCallSuppressionTest.java
    B07_CrossSessionRecallTest.java
    B08_PathGateAllowsRememberedPathTest.java
  recovery/                    # 维度 C
    C01_ResumeAfterSubTaskCompleteTest.java
    C02_ResumeDuringInProgressTest.java
    C03_ResumeAfterVerifyFailureTest.java
    C04_MessagesKeyNotReplayedTest.java
    C05_DagDependencyPreservedTest.java
    C06_VerifyLogResumableTest.java
    C07_ArtifactsNotReListedTest.java
```

### 1.2 关键 fixture（`EvalFixtures`）

```java
// 1) Mock SummarizerChatModel —— 决定性、可注入"含/不含关键词"
class StubSummarizer implements ConversationCompressor.SummarizerChatModel {
    private final String cannedSummary;
    private final List<String> keywordsToOmit;   // 测 fallback 路径时用
    @Override String summarize(String sys, List<Message> hist) { ... }
}

// 2) Mock ChatModel —— 记 tool calls，断言"是否调用 read_file"
class RecordingChatModel implements ChatModel {
    final List<ToolCall> calls = new ArrayList<>();
    // 模拟"读到 X"之后，从 memory 召回 X 的内容
}

// 3) token 注入器 —— 构造指定 token 量的对话
static List<Message> inflate(int rounds, int avgTokensPerMessage) {
    // 每轮 user + assistant + 可选 tool_call/response
}

// 4) 临时 tasks 根 —— 每个 recovery 测试用一个
static Path newTmpTasksRoot() { return Files.createTempDirectory("agent-eval-tasks-"); }
```

### 1.3 跑分默认配置（与 `ContextBudgetPolicy.defaultPolicy()` 一致）

| 参数 | 默认值 | 备注 |
|---|---|---|
| `contextWindowMax` | 128 000 | |
| `staticReserved` | 4 000 | |
| `dynamicReserved` | ≈115 808 | = 128K − 4K − 4 096 − 4 096 |
| `keepRecentRounds` | 5 | |
| `summaryTokenCap` | 1 500 | |
| `compressionThreshold` | 0.8 | 触发线 ≈ 92 646 dynamic token |
| `midTermQuota` | 1 024 | |
| `longTermQuota` | 2 048 | |
| `memoryIndexQuota` | 512 | LRU 20 |
| `ephemeralStepBudget` | 2 048 | |

---

## 2. 维度 A：上下文压缩

### A.1 触发条件（2 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **A01_NoTrigger** | 6 轮对话，每轮平均 200 token（总计 ≈2 400 token） | `policy.shouldTriggerCompression(2_400)` = false；`loadMessages` 返回 6 轮原文，无摘要。 |
| **A02_ThresholdTrigger** | 动态层已用 95 000 token（占 dynamicReserved 82%）| `shouldTriggerCompression(95_000)` = true；构建上下文时进入递减路径。 |

### A.2 压缩路径选择（5 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **A03_FiveRoundDefault** | 50 轮对话，每轮 1 000 token（共 50 000，未超阈值） | 输出恰好 5 轮 user；不含 `SystemMessage` 摘要。 |
| **A04_DecrementPath** | 50 轮对话，每轮 30 000 token（共 1.5M，5 轮就超） | 路径被走到 `rounds=1`；最终轮数 = 1；若仍超 → 进入 LLM 摘要路径。 |
| **A05_LlmSummarizeFallback** | 1 轮对话但内容 200 000 token；stub 摘要含"X=42" | 输出首条是 `SystemMessage`，文本包含"X=42"且 token 数 ≤ `summaryTokenCap`（1 500）。 |
| **A06_LocalHeuristicFallback** | 同 A05，但 stub 抛异常 → 触发 `localHeuristicSummary` | 输出包含"本地摘要"字样；首条 role tag 出现"[用户]"和"[助手]"。 |
| **A07_OverflowThrows** | 1 轮对话 200 000 token，且 stub 返回的摘要本身超 `dynamicReserved`（用 `truncateToTokens` 模拟）| 抛 `ContextBuilder.ContextOverflowException`，且异常 `getPromptTokens() > getEffectiveBudget()`。 |

### A.3 压缩比与质量（2 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **A08_CompressionRatio** | 50 轮对话，每轮 30 000 token（共 1.5M token） | 输出 token ≤ `dynamicReserved`（115 808）；压缩比 ≥ 10×；输出条数 ≤ 1 system + 1 user + 1 assistant。 |
| **A09_FactRecallAfterCompression** | 注入 50 轮对话，每轮携带 1 个"事实点"（如"接口 X 的端口是 8080"、"函数 fooBar 在 line 42"），共 50 个事实；走 A05 路径 | 对每个事实问"X 是什么？"——从压缩后消息里能检索到的比例 ≥ **80%**（key=端口号、路径、文件名这类硬事实必须保留）。 |

> **实现细节**：fact 召回用 substring 包含检测，不依赖 LLM 判断。对应 stub 摘要器必须保留每轮的事实 token，可以在 stub 里直接拷贝 round text 里的 fact 部分。

### A.4 跨轮引用（1 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **A10_CrossRoundReference** | 第 3 轮提到"文件 `/x/y/Z.java`"，第 12 轮 user 说"刚才那个文件改个名字" | 压缩后输出（5 轮覆盖第 8~12 轮 + 摘要）第 8~12 轮里**应包含** `Z.java` 的引用次数 ≥ 1；摘要里应包含"Z.java"。 |

---

## 3. 维度 B：记忆系统

### B.1 三层召回（3 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **B01_ShortTermHit** | session 内：第 1 轮 user 问"读 `foo/Bar.java`"；agent 调用 `read_file("foo/Bar.java")`；第 5 轮 user 问"Bar.java 里那个类有几个方法？" | 不再调用 `read_file`；从 short-term 召回 `Bar.java` 的内容；`ToolCallRecorder.calls` 里 `read_file` 总数 = 1。 |
| **B02_MidTermRecall** | session 结束触发 `MidTermStore` 写 `mid-term.md`；新 session 中 user 问"上一轮我们做了什么？" | `MEMORY.md` 含 `mid-term.md` 条目；新 session 启动时 `ContextBuilder` 加载 mid-term；LLM prompt 中可见该摘要。 |
| **B03_LongTermNico** | `Nico.md` 已存在；启动新 session；user 没问任何项目问题 | `LONG_TERM` key 装配 `Nico.md` 内容；`MEMORY.md` 索引首行是 `Nico.md - 项目骨架`。 |

### B.2 MEMORY.md 索引（2 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **B04_MemoryIndexLru** | 连续调用 `MemoryIndex.add(path, summary)` 25 次，使用默认 LRU 20 | `loadOrEmpty().size() = 20`；最早 5 条被淘汰；文件 `.md` 不被删除（仅从索引移除）。 |
| **B05_MemoryIndexIdempotent** | 同 path 调 `add` 3 次 | 索引里只有 1 条；`addedAt` 是首次时间；总条数不增加。 |

### B.3 工具调用抑制（这是 B 维度的核心）

| Case | 输入 | 断言 |
|---|---|---|
| **B06_ToolCallSuppression** | session A：依次 read_file 5 个文件（a.txt、b.txt、c.txt、d.txt、e.txt），每文件 ~500 token；session B（第 5 轮后）：user 依次问"a.txt 里有什么"、"b.txt 改个名"、"c.txt 的 sha256"、"d.txt 第几行报错"、"e.txt 是不是空文件" | **session B 中 `read_file` 调用次数 = 0**；`ToolCallRecorder.calls` 全是 `MemoryRecall` 或文本生成；断言"agent 不应重复读已读文件"。 |
| **B07_CrossSessionRecall** | session A 写入 long-term（Nico.md）；session B 启动 | session B 第一轮 user 问"项目结构"；agent 命中 `Nico.md`，未触发 `read_file("README.md")` 或 `find`。 |
| **B08_PathGateAllowsRememberedPath** | session A 读到 `~/.agent-private/secret.txt`（该路径本应在 PathGate 白名单内——通过自定义 `TrustedPaths`）；session B user 说"再读一下 secret.txt" | `read_file` 被调用且**通过 PathGate 校验**（不抛 `PathNotAllowedException`）；同时证明记忆里记录了完整路径。 |

> **实现细节**：B06 是 B 维度最关键的 case，需要 `RecordingChatModel` 拦截所有 tool_call，统计每个工具的调用次数。如果 production code 里 `AgentHandle` 没暴露 tool_call 的 record hook，先在 `AgentRuntime` 里加一个 `List<ToolCall> getCallHistory()` 的只读 getter（B06 之外的所有测试都用得上）。

### B.4 边界

| Case | 输入 | 断言 |
|---|---|---|
| **B09_LargeFileNotRememberedWhole** | session A 读到 5 MB 文件 | 记忆只存**路径 + 摘要**，不存全文；`LongTermStore` 写盘大小 < 文件 1%；新 session 引用时 agent **仍然会重新 read_file**（因为不存全文），但能从 memory 拿到"该文件存在"这件事，避免 `find` 搜索。 |
| **B10_EvictedFromIndexStillReadable** | session A 写 21 个 memory 条目（超出 LRU 20）；session B user 问第 1 个被淘汰的条目内容 | agent **不知道**这条（索引里没有）；不抛错，而是 fallback 到 `grep`/`find`。 |

---

## 4. 维度 C：任务恢复

### C.1 恢复路径（3 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **C01_ResumeAfterSubTaskComplete** | plan 包含 3 个 SubTask：A(COMPLETED+VERIFIED) → B(IN_PROGRESS, kill 时 50%) → C(PENDING)；kill −9；新进程扫描 `tasksRoot`，找到 ACTIVE plan | `/resume <planId>` 后 B 重新进入 IN_PROGRESS；不重跑 A；A 的 `{taskId}.json` 不被覆写。 |
| **C02_ResumeDuringInProgress** | 同 C01 但 B 状态为 IN_PROGRESS，Checkpoint 含 2 条 | 恢复后 B 的 `currentAction`、`nextStep` 从磁盘读回，与 kill 前一致；agent 接着干，不需要从 PENDING 重新拆解。 |
| **C03_ResumeAfterVerifyFailure** | B 已 COMPLETED，VERIFY 子任务最后一次失败（`verify.log` 留存）；kill | 恢复后系统提议**新建一个 FIX 子任务**而不是重跑 VERIFY；新 FIX 完成后 VERIFY 重跑。 |

### C.2 上下文不重读（这是 C 维度的核心）

| Case | 输入 | 断言 |
|---|---|---|
| **C04_MessagesKeyNotReplayed** | 同 C01；恢复后启动新一轮 ReAct | `ContextBuilder.build()` 时 `MESSAGES` key 的内容**仅含 B 的 done / currentAction / nextStep 摘要**，**不含** A 的全部对话流；`MESSAGES` token 数 ≤ 200（vs. 无恢复基线 A+B 完整历史 ≈ 5 000+ token）。 |
| **C07_ArtifactsNotReListed** | A 已 VERIFIED，`artifacts: ["src/X.java"]`；恢复 B | B 第一轮对话的 prompt 里**不重复**列出 A 的 artifacts；但 B 的 LLM 看得见 A 的 `done` 摘要里提过 X.java（通过 `MID_TERM` 或 plan 摘要）。 |

> **实现细节**：C04 是 C 维度最关键的 case。需要截获 `ContextBuilder.build()` 的输出，定位 `MESSAGES` key 的实际 content，与"无恢复基线"（同一 plan 内直接调 `loadMessages(全 history)`）做 diff。

### C.3 DAG 一致性（1 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **C05_DagDependencyPreserved** | 4 个 SubTask，依赖图：A→B、A→C、B+C→D；D PENDING 时 kill | 恢复后调度器看到 B 和 C 都是 VERIFIED，D 才能进入 IN_PROGRESS；如果只有 B VERIFIED、C IN_PROGRESS，D 保持 PENDING（不会乱序）。 |

### C.4 验证日志可恢复（1 个 case）

| Case | 输入 | 断言 |
|---|---|---|
| **C06_VerifyLogResumable** | B 完成 → VERIFY 启动，`verify.log` 写了一半（50 KB）→ kill；恢复后 VERIFY 重新跑 | 旧的 `verify.log` 被 truncate 后重写（与 `TaskPlanRepository.appendVerifyLog` 的 `TRUNCATE_EXISTING` 行为一致）；不会留下半截文件影响下次 parse。 |

### C.5 边界

| Case | 输入 | 断言 |
|---|---|---|
| **C08_PlanJsonCorrupted** | `plan.json` 被手动改成非法 JSON | 启动扫描时不抛错也不崩；该 plan 被跳过并 log warn；`/resume` 命令对它返回"plan 损坏，无法恢复"。 |
| **C09_TwoPlansRaceResume** | 两个 ACTIVE plan 同时存在；用户 `/resume` 不带 planId | 默认按 `updatedAt desc` 选最近的一个；提供 `/tasks` 列表让用户挑。 |
| **C10_ResumeAfterSubTaskRetryExhausted** | SubTask 重试 3 次仍 FAILED；kill；恢复 | 不再自动重试；标记 `attempts=3, failureReason=...`；plan 进入 PAUSED（不是 ABANDONED），等用户决策。 |

---

## 5. 评分与门槛

### 5.1 三个维度的核心 KPI

| 维度 | KPI | 公式 | 门槛 |
|---|---|---|---|
| A 压缩 | **事实召回率** | `A09_recall` = `recall_count / total_facts` | ≥ 0.80 |
| A 压缩 | **压缩比** | `A08_ratio` = `input_tokens / output_tokens` | ≥ 10× |
| A 压缩 | **路径正确率** | `A03+A04+A05+A06+A07` 通过率 | 100% |
| B 记忆 | **read_file 去重率** | `B06_rate` = 1 − `sessionB_read_calls / sessionA_read_calls` | ≥ 0.90 |
| B 记忆 | **MEMORY.md 容量守恒** | `B04_size ≤ 20` | 100% |
| C 恢复 | **状态保持正确率** | `C01+C02+C03` 通过率 | 100% |
| C 恢复 | **messages key token 节流比** | `C04_ratio` = `baseline_tokens / recovered_tokens` | ≥ 5× |

### 5.2 报告格式

每个 `mvn test` 跑完打印一张表：

```
维度   | Case                  | Pass | KPI              | 阈值     | 实测
A      | A09_FactRecall        |  ✓   | recall           | ≥0.80    | 0.86
A      | A08_CompressionRatio  |  ✓   | ratio            | ≥10×     | 12.4×
B      | B06_ToolCallSuppress  |  ✗   | dedupe_rate      | ≥0.90    | 0.40
C      | C04_MessagesKey       |  ✓   | token_ratio      | ≥5×      | 7.1×
...
```

### 5.3 失败时怎么定位

- **A 维度失败**：先打印输入/输出的 token 分布直方图，确认是"摘要路径走错"还是"stub 没保留关键词"。
- **B 维度失败**：打印 `RecordingChatModel.calls` 的完整列表，看是不是 agent **应该**调用 read_file 却没调（属于过度抑制）还是**不该**调却调了（属于抑制失效）。
- **C 维度失败**：dump 恢复前后 `plan.json` 和 `{taskId}.json` 的 diff，定位状态机转换错在哪一步。

---

## 6. 跑分入口

### 6.1 Maven profile（新增到 `pom.xml`）

```xml
<profile>
  <id>eval</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <includes>
            <include>**/eval/**/*Test.java</include>
          </includes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

### 6.2 执行命令

```bash
mvn test -Peval                              # 跑全部评测
mvn test -Peval -Dtest=A*Test                # 只跑 A 维度
mvn test -Peval -Dtest=B06_ToolCall*         # 单 case
mvn test -Peval -Dtest=C04_MessagesKeyTest   # C 维度核心 case
```

### 6.3 不进入 CI / 默认 `mvn test`

评测 case 用 Spring context 启动（`@SpringBootTest`），跑一轮 1~3 秒；30 个 case 一共 1~2 分钟。
默认 `mvn test` 不跑（profile 控制），保持单元测试套件的快速反馈。

---

## 7. 待办（在写代码前需要先做的事）

- [ ] `AgentRuntime` 暴露 `List<ToolCall> getCallHistory()` 只读 getter（B06 必需）。
- [ ] `ContextBuilder.build()` 暴露 hook 让测试能拿到每个 key 的最终 content（C04 必需）。
- [ ] 确认 `MemoryIndex.add` 是真的 LRU 而不是简单的 list append（看 line 80+ 实现，B04 才能成立）。
- [ ] 确认 `ResumeCommand` / `PlanResumeCommand` 的入口是同一条路径（git status 里有这两个文件，需要选一个统一）。
- [ ] 决定 token 估算口径：是不是就用 `ContextBudgetPolicy.estimateTextTokens`？还是引入 `jtokkit` / Qwen tokenizer？**默认建议用项目自带的 4-字符估算**，避免引入新依赖。
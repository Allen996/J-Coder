# 任务系统 DAG 与新记忆模型 — 最终架构文档

本文档记录三层记忆重设计后,任务编排子系统的**最终落地架构**(阶段 1-5 全部完成)。

---

## 1. 背景

任务系统经过四轮重写(阶段 1-5),从最初的 SubTask 状态机演进到当前基于 DAG + SubAgent 异步派发的设计。所有 VERIFY/FIX/SKIP 级联逻辑已删除,ToolResultStore 外置缓存已删除,Checkpoint 从进度打点升级为完整决策快照。

---

## 2. 当前架构核心组件

### 2.1 DAG 层(`core/task/dag/`)

- `DagGraph` —— DAG 静态结构(plan.json),仅持有 taskId/title/description/dependsOn/expectedOutput
- `DagNode` —— 节点(静态字段 + 运行时字段 state/attempts/lastResult)
- `DagState` —— DAG 运行时状态(dag-state.json),按 taskId 持有 runtime 节点
- `DagStateRepository` —— 落盘到 `.agent/sessions/{sessionId}/{plan.json|dag-state.json}`
- `DagNodeState` 枚举:`PENDING / IN_PROGRESS / COMPLETED / FAILED / TIMEOUT`
- `DagPlanStatus` 枚举:`RUNNING / COMPLETED / FAILED / ABANDONED`

### 2.2 SubAgent 层(`core/task/subagent/`)

- `SubAgentRunner` 接口 —— `SubAgentResult run(SubAgentTask task)`
- `LocalSubAgentRunner` 实现 —— 同步 + 超时 + cancel;完成时调 `SessionCompressor.compressIfPresent`
- `SubAgentTask` record —— taskId/title/description/expectedOutput/contextFiles/parentSessionId/parentCheckpointId/timeoutMs
- `SubAgentResult` record —— taskId/status(COMPLETED/FAILED/TIMEOUT)/report/reason/artifacts/toolCalls/durationMs/sessionPath

### 2.3 编排层(`core/task/orchestrator/`)

- `TaskOrchestrator` —— 单实例,持有 activeGraph + activeState + pending FutureTask map
- 通过 Spring `TaskExecutor` 异步派发 SubAgent(主 loop 同步 `pollPendingSubagents()` 汇总)
- 暴露 `activeGraph() / activeState() / getCurrentCheckpointId() / getDagStateRepository()`
- FAILED/TIMEOUT 节点允许重试(L.4 决定,主 Agent 显式再调 dispatch)

### 2.4 Checkpoint 层(`core/task/Checkpoint.java` + `CheckpointService.java`)

- 触发时机:主 Agent 主动调 `checkpoint` 工具 / 用户 `/checkpoint` 命令
- 写入路径:`.agent/sessions/{sessionId}/checkpoints/{seq}-{reason}/manifest.json`
- 包含字段:system prompt / 工具 registry snapshot / DAG / worklog tail seq / mid-term seq / long-term 文件清单 / git commit hash / 决策上下文 / PENDING_DECISION→DECIDED→ABANDONED 状态
- git 必须可用(`git rev-parse HEAD`),不可用则拒绝写入

### 2.5 三层记忆

- **短期 worklog** —— `short-term.json`,append-only,所有事件写入
- **中期压缩** —— `mid-term.json`,`SessionCompressor` 在 SubAgent 完成后折叠
- **长期主题** —— `{projectRoot}/NNN-<topic>.md`,多个 markdown 文件,`LongTermStore` 管理

---

## 3. 删除的旧组件(阶段 5 清理)

| 旧组件 | 删除原因 |
| --- | --- |
| `SubTask` / `SubTaskSpec` / `SubTaskStatus` / `SubTaskType` | 被 `DagNode` / `DagNodeState` 取代 |
| `TaskPlan` / `TaskPlanStatus` / `PlanEdge` | 被 `DagGraph` 取代 |
| `TaskScheduler` | 不再有 VERIFY/FIX 配置调度 |
| `TaskLoopObserver` / `TaskPlanTools` / `TaskLoopObserverHub` | 旧 observer 机制被新 DAG 取代 |
| `TaskPlanRepository` | 被 `DagStateRepository` 取代 |
| `VerifyCommandTemplate` / `VerifyRunner` / `VerifyResult` | 不再自动跑 mvn/gradle 验证 |
| `AgentRuntimeSubTaskExecutor` / `SubTaskExecutor` / `SubTaskOutcome` | 旧 SubTask 执行器被 SubAgent 取代 |
| `TaskOrchestratorConfig` | 没有 VERIFY 失败上限等配置需要 |
| `RecallToolResult` (tool) / `FileSystemToolResultStore` / `ToolResultStore` / `ToolResultRecord` | 工具结果不再外置占位符(#id),走 short-term 全量记录 |
| `ToolGateway.appendStoredId` | 不再追加 `[stored as #<id>]` 提示 |
| `ReActLoop.autoInlinePlaceholders` | 无 #<id> 占位符需要 inline |
| `FileTools.resultStore.invalidateByPath` | 无缓存需要失效 |
| `TaskProgressRenderer` / `TaskEventPublisher` / 旧 TaskEvent 体系 | 渲染直接用 `DagStateRepository` 读盘 |
| `PlanResumeCommand` | 旧 plan-resume 流程被取消 |

---

## 4. 工具物理隔离(阶段 1)

- `ToolDescriptor` record 加 `mainAgentOnly` 字段
- `ToolDescriptorRegistry` 标记主 Agent 专属工具:`create_plan`, `dispatch_subtask`, `append_subtask`, `checkpoint_now`, `inspect_subagent` 等
- `SpringAiReactAgentProvider.collectToolObjects(agentRole)` 按 role 过滤:SubAgent 拿不到主 Agent 专属工具
- `run_shell` 默认给 SubAgent(Q3 决定)

---

## 5. 测试覆盖

阶段 1-5 测试套件:

- `ToolDescriptorRegistryTest` (4 tests)
- `SpringAiReactAgentProviderTest` (5 tests)
- `SubAgentTaskTest` (5 tests)
- `LocalSubAgentRunnerTest` (5 tests)
- `DispatchSubtaskToolTest` / `CreatePlanToolTest` / `AppendSubtaskToolTest` / `InspectSubagentToolTest` / `CheckpointToolTest` (各自 4-6 tests)
- `TaskOrchestratorTest` (20 tests)
- `SessionCompressorTest` (6 tests)
- `CheckpointServiceTest` (10 tests)

总计 264 tests,其中 19 errors 来自 Mockito + JDK 23 attach 问题(预存问题,与本架构无关)。

---

## 6. 未来扩展方向(留作下一轮)

- 撤销/回滚语义:`SubAgentTask.parentCheckpointId` 已就位,主 Agent 收到 checkpoint id 后可触发"回到 checkpoint 状态"
- 跨 session 续做:plan.json 按 sessionId 隔离,跨 session 续做需要重新设计元数据
- 实时上下文压缩:目前 build() 时同步触发,流量大时可改为后台异步
- 跨进程多 plan 并行:当前是单 active plan,多 plan 并行调度需要新的并发模型

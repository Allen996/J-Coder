# Part 1 · Agent 平台设计

> 模块代号：`agent-platform`
> 设计目标：把 SuperBizAgentV2 中散落在 `ChatService` / `ChatController` / `AiOpsService` 里的"Agent 拼接代码"抽离出来，形成可复用、可观测、可治理的 Agent 基础平台。
> 覆盖范围：**ReAct 运行时 + 提示词工程 + 上下文工程 + 多 Agent 编排 + 沙箱机制**。

---

## 1. 目标与边界

### 1.1 目标

| 维度 | 现状痛点 | 本平台目标 |
|---|---|---|
| 复用性 | ReAct Agent 的构建逻辑散落在 `ChatService.createReactAgent` 和 `AiOpsService.buildPlanner/Executor/Supervisor` 三处 | 统一 `AgentRuntime` 工厂，任何业务方一行代码拿到一个治理完备的 ReAct Agent |
| 提示词 | 全部是 Java 字符串（`AiOpsService.buildPlannerPrompt` 等） | Prompt 抽到 `resources/prompts/*.yaml`，支持版本、灰度、A/B、Jinja 渲染 |
| 上下文 | `MAX_WINDOW_SIZE = 6` 硬编码滑动窗口，`messageHistory` 在内存里 | 三层记忆（短期/工作/长期），Token 预算驱动压缩，持久化到 Redis |
| 多 Agent | `SupervisorAgent.builder()` 三个 hardcoded subAgent | 声明式编排（YAML/JSON 定义拓扑），路由、终止、人机交接由状态机统一管控 |
| 沙箱 | 无沙箱，工具调用直接打到本地 | 工具沙箱化（Sidecar / Docker / WASM），网络白名单、CPU/内存/超时三道闸口 |
| 可观测 | 只有 SLF4J | OpenTelemetry trace + 决策日志 + 工具调用重放 |
本平台只提供"Agent 怎么跑得稳、怎么跑得久、怎么跑得安全"，**不关心 Agent 在做什么业务**。

---

## 2. 顶层架构

```
┌──────────────────────────────────────────────────────────────────┐
│                       agent-platform 模块                         │
│                                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌───────────┐ │
│  │   Prompt   │  │  Context   │  │  Multi-    │  │  Sandbox  │ │
│  │   Registry │  │  Engine    │  │  Agent     │  │  Manager  │ │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬─────┘ │
│        └─────────┬─────┴─────────┬─────┘               │       │
│                  ▼               ▼                     │       │
│            ┌──────────────────────────┐               │       │
│            │      AgentRuntime        │◀──────────────┘       │
│            │  (ReAct Loop Engine)     │                       │
│            └──────────┬───────────────┘                       │
│                       │                                       │
│            ┌──────────▼───────────────┐                       │
│            │    Tool Gateway          │                       │
│            │ (MCP / Function / HTTP)  │                       │
│            └──────────────────────────┘                       │
└──────────────────────────────────────────────────────────────────┘
```

调用方向：`业务服务 → AgentRuntime → (Prompt Registry + Context Engine + Tool Gateway [+ Sandbox])`

---

## 3. Maven 拆分

```
super-biz-agent-parent (pom)
├── agent-platform              ← 本文档落地的核心模块
│   ├── agent-core              (ReAct 循环 + AgentRuntime)
│   ├── agent-prompt            (Prompt Registry + Jinja 渲染 + 版本)
│   ├── agent-context           (Context Engine + Memory)
│   ├── agent-orchestration     (Multi-Agent 状态机)
│   ├── agent-sandbox           (Tool 沙箱)
│   └── agent-observability     (OTel + 决策日志 + 重放)
├── biz-api                     ← 现有 ChatController 改成业务 API
└── infra                       ← 配置中心 / Prometheus / CLS 等 SDK 封装
```

`biz-api` 只引用 `agent-platform` 暴露的 facade，不直接接触 Spring AI Alibaba 的 `ReactAgent.builder()` 链式 API。

---

## 4. 模块设计

### 4.1 agent-core · ReAct 运行时

#### 4.1.1 抽象

```java
public interface AgentRuntime {
    AgentExecutionResult execute(AgentTask task);
    Flux<AgentEvent> stream(AgentTask task);
    AgentHandle cancel(String executionId);
}
```

`AgentRuntime` 是对 Spring AI Alibaba `ReactAgent` 的薄封装 + 治理增强。它把当前 `ChatController` / `ChatService` 里的"创建 → 调用 → 拼 SSE → 更新会话历史"流水线标准化。

#### 4.1.2 ReAct 循环的状态机

当前实现是把"思考 / 行动 / 观察"完全交给 Spring AI Alibaba 的内部实现。**问题在于**：超时、token 超限、循环僵死都没法干预。本平台插入一层 `ReActLoopObserver`：

```java
public interface ReActLoopObserver {
    void onThought(ThoughtEvent e);              // 推理步骤
    void onActionPreCheck(ActionPreCheckEvent e);// 工具调用前：参数校验、权限、Token 预算
    void onActionInvoked(ActionInvokedEvent e);  // 工具已调用
    void onObservation(ObservationEvent e);      // 工具返回
    void onLoopBudgetExceeded(LoopBudgetEvent e);// 单轮 Step 数超限
    void onTokenBudgetExceeded(TokenBudgetEvent e);
    void onFinish(FinishEvent e);
    void onError(LoopErrorEvent e);
}
```

`AgentRuntime` 内部用 `List<ReActLoopObserver>` 串成观察者链：

- `TokenBudgetObserver`：累计 prompt+completion tokens，超阈值就 `finish(reason=TOKEN_LIMIT)`。
- `LoopStepObserver`：单次任务最多 N 步（默认 12），超了强制 `finish(reason=LOOP_LIMIT)` 并把当前观察摘要返回。
- `TimeoutObserver`：整次任务和单步工具调用都设上限，超时立即 abort。
- `SandboxObserver`：调用前先过沙箱审批。

#### 4.1.3 取消与恢复

`AgentHandle cancel(executionId)` 通过 `ConcurrentHashMap<executionId, FluxSink>` 实现协作式取消。当前 SSE 客户端断开连接时自动触发。

恢复：用 `AgentExecutionRecord` 持久化每一步的 thought/action/observation，下次相同 `executionId` 可从断点继续（用于长任务重试）。

#### 4.1.4 关键类

| 类 | 职责 |
|---|---|
| `AgentRuntimeImpl` | ReAct 主循环 |
| `ReActLoop` | 单次 step：model.call → parseAction → toolGateway.invoke → emit observation |
| `AgentExecutionRecord` | 执行快照（用于恢复、重放、审计） |
| `AgentTask` | 输入：messages + tools + budget + sessionRef |
| `AgentExecutionResult` | 输出：final answer + 全量 event log |

---

### 4.2 agent-prompt · 提示词工程

#### 4.2.1 文件组织

不再用 Java 字符串。统一放：

```
src/main/resources/prompts/
├── v1/
│   ├── react-assistant.system.yaml
│   ├── planner.system.yaml
│   ├── executor.system.yaml
│   └── supervisor.system.yaml
├── v2/
│   └── ...
└── _active/
    ├── react-assistant.system    →  v2/react-assistant.system.yaml  (软链 / 指针)
    ├── planner.system
    └── ...
```

每个 YAML：

```yaml
id: planner.system
version: 2
labels: [aiops, planning]
template: |
  你是 Planner Agent ...
  {# 当前任务 #}
  任务：{{ task }}
  历史决策：{{ history | tojson }}
  ...
parameters:
  temperature: 0.3
  max_tokens: 4000
  top_p: 0.9
guards:
  forbidden_phrases: ["编造", "猜测"]
  must_include_tools_in_plan: ["queryPrometheusAlerts"]
```

#### 4.2.2 Prompt Registry

```java
public interface PromptRegistry {
    Prompt resolve(String id, PromptContext ctx);          // 渲染 + 注入变量
    List<PromptVersion> history(String id);
    void rollback(String id, int version);
    PromptVersion publish(String id, String yamlBody, String author);
}
```

实现要点：
- **热加载**：监听 `prompts/` 目录变化（`WatchService`），无需重启。
- **灰度**：发布时指定 `traffic_percent`，底层按 `sessionId.hashCode() % 100 < percent` 路由到新旧版本，埋点对比。
- **A/B**：配合 `agent-observability` 输出 `prompt_version × task_success_rate` 看板。
- **变量注入**：`PromptContext` 提供 task、history、tools、role、tenant 等命名空间，避免 LLM 看到全部内部状态。

#### 4.2.3 防注入与守门

- `guards.forbidden_phrases`：渲染后正则扫，命中就拒发该 prompt 并记 trace。
- `guards.max_input_tokens`：超长输入自动截断或抽象（交给 `Context Engine` 处理）。
- `must_include_tools_in_plan`：Planner 输出的 plan JSON 必须包含指定工具名，否则强制重规划一次。

#### 4.2.4 复用现成代码的迁移路径

把 `AiOpsService.buildPlannerPrompt()` 内的多行字符串原样搬到 `prompts/v1/planner.system.yaml`。`AiOpsService` 改成：

```java
Prompt prompt = promptRegistry.resolve("planner.system", ctx);
ReactAgent.builder()
    .name("planner_agent")
    .systemPrompt(prompt.rendered())
    .systemPromptParams(prompt.params())   // 替换原硬编码 temperature
    ...
```

这样**业务代码一行不动**，仅把字符串外包出去。

---

### 4.3 agent-context · 上下文工程

当前 `ChatController.SessionInfo` 是**全量消息列表 + 简单 FIFO**。这种做法在长会话、复杂任务里会：
1. 撑爆上下文窗口
2. 关键证据被无关对话稀释
3. 跨会话无法复用

#### 4.3.1 三层记忆

```
┌────────────────┐
│ Working Memory │  ← 当前 ReAct 循环内的 observations（自动滚动）
│  (per-loop)    │
└────────┬───────┘
         │ 蒸馏/摘要
┌────────▼───────┐
│ Short-term     │  ← 当前会话的最近 K 轮对话 + 任务摘要
│  (per-session) │
└────────┬───────┘
         │ 关键事件固化
┌────────▼───────┐
│ Long-term      │  ← 跨会话：用户偏好、领域知识、过往事件
│  (per-tenant)  │
└────────────────┘
```

#### 4.3.2 实现

| 层级 | 存储 | 写入策略 | 读取策略 |
|---|---|---|---|
| Working | 内存（每线程） | ReAct loop 自动 push/evict | loop 直接遍历 |
| Short-term | Redis (TTL = session TTL) | 每次 turn 结束持久化；超 8 轮触发摘要压缩 | `getSession(sessionId)` 一次拉取 |
| Long-term | Postgres + 向量库（Milvus） | 由 `MemoryConsolidator` 后台任务从 short-term 摘要 + 用户反馈中提取 | RAG 召回 top-k |

#### 4.3.3 Token 预算驱动的压缩

```java
public class ContextBudgetPolicy {
    int totalBudget;            // 例如 128k
    int systemReserved;         // system prompt 配额
    int toolsReserved;          // tool schemas 配额
    int workingReserved;        // 当前 loop 工作记忆
    int shortTermReserved;      // 会话历史

    int availableForLongTerm(); // = totalBudget - 上面四个
}
```

`ContextEngine.buildMessages(task)` 流程：

1. 固定占用：`system` + `tool_schemas`
2. 注入 `workingMemory`：按事件时间倒序，直到撑满 `workingReserved`
3. 注入 `shortTerm`：用 `summary(older) + recent turns` 形式，撑满 `shortTermReserved`
4. 余量允许时，用当前 task 检索 long-term 召回 top-k 注入
5. 任何超出都触发**渐进式压缩**（细节删 → 旧 turn 折叠成一句 → 极端情况只保留 system + task）

#### 4.3.4 关键类

| 类 | 职责 |
|---|---|
| `ContextEngine` | 装配 messages、压缩、token 计数（用 jtokkit 或模型自带 tokenizer） |
| `WorkingMemory` | ReAct loop 内的事件队列 |
| `ShortTermMemoryStore` | Redis 实现 |
| `LongTermMemoryStore` | Postgres + Milvus 实现 |
| `MemoryConsolidator` | 后台 scheduler，定期蒸馏 short → long |
| `ContextBudgetPolicy` | 预算策略，按 agent 类型不同（ReAct vs Planner） |

---

### 4.4 agent-orchestration · 多 Agent

当前 `AiOpsService` 用三个 `ReactAgent.builder()` + `SupervisorAgent.builder()` 拼出来一个多 Agent。问题：
1. 拓扑是 Java 代码硬编码，改流程要发版
2. 终止条件靠 prompt 里的 "3 次失败就停"
3. 没有共享状态序列化、人机交接

#### 4.4.1 声明式编排

把多 Agent 拓扑写成 YAML：

```yaml
# orchestration/incident-diagnosis.yaml
name: incident-diagnosis
entry: supervisor

nodes:
  - id: supervisor
    type: llm-router
    model: qwen3-max
    prompt: supervisor.system
    routes:
      - when: "needs_plan_replanning"
        to: planner
      - when: "needs_step_execution"
        to: executor
      - when: "decision == FINISH"
        to: END

  - id: planner
    type: react
    model: qwen3-max
    prompt: planner.system
    output_key: planner_plan
    tools: [queryPrometheusAlerts, queryInternalDocs, getCurrentDateTime]

  - id: executor
    type: react
    model: qwen3-max
    prompt: executor.system
    output_key: executor_feedback
    tools: [queryLogs, queryPrometheusAlerts, runSkill:cpu-diagnosis]

  - id: human-approval
    type: human-gate
    when: "executor.proposes_action AND action.severity >= HIGH"
    timeout: 30m
    on_timeout: escalate

transitions:
  planner → supervisor: on planner.finish
  executor → supervisor: on executor.finish
  human-approval → executor: on approve
  human-approval → END: on reject
```

#### 4.4.2 状态机引擎

`MultiAgentEngine.execute(workflowId, initialContext)`：

1. 加载工作流定义（缓存）
2. 进入 `entry` 节点
3. 执行节点（`react` / `llm-router` / `human-gate` / `tool-call` / `skill-call`）
4. 根据节点产出 + 工作流 `transitions` 决定下一跳
5. 终止条件显式定义：`max_total_steps`、`max_wallclock`、`require_human_signoff_for_severity >= HIGH`
6. 任何状态变化都持久化到 `agent_orchestration_state` 表，重启可恢复

#### 4.4.3 共享状态

`OverAllState`（Spring AI Alibaba 现成的）保留，但**外层包一层 `WorkflowContext`**：

```java
class WorkflowContext {
    String workflowId;
    String executionId;
    String tenantId;
    Map<String, Object> inputs;
    Map<String, Object> sharedState;   // 各节点 output_key 聚合
    List<StepRecord> history;          // 完整轨迹
    Budget budget;
}
```

`OverAllState` 只作为 `sharedState` 的运行时载体，**真实持久化在 Postgres**（`workflow_execution` 表 + `workflow_step` 表）。

#### 4.4.4 人机交接

`human-gate` 节点类型：
- 暂停工作流
- 通过 IM（钉钉/企微/Slack）发送审批卡片
- 用户点同意 / 拒绝 / 修改参数
- Webhook 回写决策 → 工作流继续
- 超时按 `on_timeout` 配置处理（默认 escalate）

#### 4.4.5 冲突解决

当多个 subAgent 写同一个 `output_key` 时：
- **最后写入胜出**（默认）
- **显式合并**（节点声明 `merge_strategy: concat | summarize | vote`）
- **严格互斥**（节点声明 `exclusive_keys`）

---

### 4.5 agent-sandbox · 沙箱机制

当前 4 个 tool 全部直连本地/远程服务。**真实风险**：
- `queryLogs` 把任意 SQL-like query 喂给 CLS（用户控制）
- 未来增加 `executeCommand` 类工具会直接打到生产 Pod
- 异常 tool 返回（OOM、巨大字符串）会撑爆 LLM context

#### 4.5.1 三道闸口

```
Agent → Tool Call
       │
       ▼
[1] Schema 闸   参数类型 / 必填项 / 长度（JSON Schema 自动校验）
       │
       ▼
[2] Policy 闸   白名单 (按 tool 维度) / 黑名单 (按参数 keyword)
       │          / 速率限制 (每分钟 N 次) / 并发限制 (全局 M 个)
       │
       ▼
[3] Runtime 闸  超时 / 内存 / CPU / 网络出口 / 文件系统只读
```

#### 4.5.2 沙箱实现（按 tool 风险分级）

| Tool 类别 | 沙箱方式 | 说明 |
|---|---|---|
| 只读查询（Prometheus、CLS、内部文档） | **进程内隔离** + 超时 | 单线程超时 + result size cap（默认 100KB） |
| 写操作（K8s patch、CMDB 更新） | **Sidecar gVisor** + 双签 | 高风险，必须人工审批（见 `human-gate`） |
| 代码执行（execute_python 之类） | **WASM（wasmtime）** | 沙箱内无网络、写只允许指定目录、CPU 限时 |
| 任意 HTTP 调用（web_fetch） | **出站白名单** | 域名/IP 白名单 + 强制 HTTPS + 响应体大小限制 |

#### 4.5.3 关键类

| 类 | 职责 |
|---|---|
| `ToolGateway` | 工具调用总入口，所有 ReAct loop 必经 |
| `ToolInvocation` | 不可变结构，记录 tool、参数、调用时间、来源 agent |
| `PolicyEngine` | 规则引擎（OPA 或自研 DSL） |
| `SandboxRuntime` | 抽象接口，4 个实现：InProcess / GVisorSidecar / Wasm / RestrictedHttp |
| `AuditLogger` | 异步写审计日志到专用库（不可被业务删） |

#### 4.5.4 审计日志 schema

```sql
CREATE TABLE tool_invocation_audit (
  id            BIGSERIAL PRIMARY KEY,
  execution_id  TEXT NOT NULL,
  workflow_id   TEXT,
  agent_name    TEXT,
  tool_name     TEXT NOT NULL,
  args_hash     TEXT,                 -- 不存原文，存 hash 防泄密
  args_redacted JSONB,                -- 脱敏后参数
  result_size   INT,
  result_redacted JSONB,
  latency_ms    INT,
  status        TEXT,                 -- ok / policy_denied / timeout / error
  error         TEXT,
  sandbox       TEXT,                 -- in_process / gvisor / wasm / http
  created_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX ON tool_invocation_audit (execution_id);
CREATE INDEX ON tool_invocation_audit (created_at DESC);
```

---

### 4.6 agent-observability · 可观测

#### 4.6.1 三层信号

| 层级 | 内容 | 落点 |
|---|---|---|
| Trace | ReAct 每一步 + Tool 调用 + LLM 调用 → span | OTel → Jaeger/Tempo |
| Metric | 步数、token、成功率、latency p50/p95 | OTel → Prometheus |
| Log | 决策摘要、用户反馈、人工接管事件 | 结构化 JSON → Loki/ES |

#### 4.6.2 决策日志

不只打"调了哪个工具"，还要打"为什么调它"：

```json
{
  "ts": "2026-07-07T10:23:45.123Z",
  "execution_id": "...",
  "agent": "planner",
  "step": 3,
  "thought_summary": "CPU 高占用告警持续 25 分钟，需先确认受影响实例",
  "decision": "EXECUTE queryPrometheusAlerts",
  "rationale": "根据 runbook step1，先确认告警范围",
  "tokens_in": 1823,
  "tokens_out": 234
}
```

#### 4.6.3 重放器

`ReplayService.replay(executionId, mode=step|continuous)`：
- 从 `agent_execution_record` 加载历史
- 可选：固定原 LLM 响应（验证修复） / 重新调 LLM（对比）

这是评估 prompt 改动的核心工具。

---

## 5. 业务侧改造示例（最小入侵）

以现有 `ChatController` 为例，迁移后的 `chatStream`：

```java
@PostMapping(value = "/chat_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter chatStream(@RequestBody ChatRequest req) {
    SseEmitter emitter = new SseEmitter(300_000L);

    AgentTask task = AgentTask.builder()
        .sessionId(req.getId())
        .input(req.getQuestion())
        .promptId("chat.react-assistant")           // ← 走 Prompt Registry
        .budget(AgentBudget.chat())                 // ← 走 ContextBudgetPolicy
        .sandboxProfile(SandboxProfile.CHAT_DEFAULT)
        .build();

    agentRuntime.stream(task).subscribe(
        event -> emitter.send(SseMessage.from(event)),
        err    -> emitter.completeWithError(err),
        ()     -> emitter.complete()
    );
    return emitter;
}
```

Controller 从 130 行（流式版本）压到 15 行。所有 ReAct 细节、提示词、上下文、沙箱都在 `agent-platform` 里。

---

## 6. 演进路线

| 阶段 | 内容 | 验证标准 |
|---|---|---|
| M1 | agent-core + agent-prompt（v1 YAML 化现有 3 个 prompt） | 现有 3 个 demo 行为不变，prompt 可热更新 |
| M2 | agent-context 短期 + Working | 长对话（>20 轮）token 不爆 |
| M3 | agent-orchestration 状态机 + 人机 gate | AiOps 流程可暂停 / 恢复 |
| M4 | agent-sandbox（先 InProcess + 审计日志） | 所有 tool 调用有审计、policy 可拒绝 |
| M5 | agent-observability + Replay | 改 prompt 后能用 replay 对比 |

---

## 7. 与 Part 2 的衔接

`agent-platform` 完全不知道"什么是告警、什么是 PromQL"。Part 2 的 `aiops-platform` 会：

- 通过 `AgentTask` 传入 `promptId=aiops.incident-diagnosis`
- 在工具层注册 MCP 适配器（Prometheus / CLS / K8s）
- 通过 `runSkill` 节点调用 `skill.cpu-diagnosis` 等可复用诊断流程
- 通过 `human-gate` 落地执行审批

**两块边界**：agent-platform 只负责"Agent 怎么跑"，aiops-platform 负责"Agent 跑什么业务"。改业务不动平台，改平台不动业务。
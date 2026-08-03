## 8. 任务系统

### 8.1 为什么需要任务系统

agent 当前能"完成一件事"，但不能"完成一件被拆解过、彼此依赖、每一步都留痕、最后一步强制验证"的事。当用户提出一个跨越多文件、多个能力领域的请求（如"实现 X 功能并接入测试"）时,ReAct Loop 会在 `ephemeral` 流里把每一步冲走,失败时也找不到"哪一步没做完"。任务系统解决的是 **协作层面的可追溯性**,不是单次执行的工具调用。

四个核心约束:

1. **拆解**:一个用户请求可被拆成多个子任务。
2. **依赖**:子任务间存在偏序关系(A 完成后才能做 B)。
3. **留痕**:每个子任务的状态、起止时间、产物、失败原因全部落盘,中途退出可恢复。
4. **强制验证收尾**:一个任务计划的最后一步必须是"运行代码/测试",通过才算 plan 完成。

### 8.2 概念分层

引入三个新概念,严格与已有概念区分:

| 概念 | 粒度 | 生命周期 | 落盘 | 与现有层的关系 |
|---|---|---|---|---|
| **TaskPlan** | 用户的一次完整请求 | 跨多轮对话直到全部完成或放弃 | `.agent/tasks/{planId}/plan.json` | 1 个 plan 对应 1 个或多个 `AgentTask` |
| **SubTask** | plan 内一个独立可完成的子目标 | 从 PENDING 到 COMPLETED/FAILED | `.agent/tasks/{planId}/{taskId}.json` | 1 个 SubTask 通常对应 1 个 `AgentTask`(也可多个 step 串成一个) |
| **Checkpoint** | SubTask 内一次可恢复的进度快照 | 随 SubTask 存活,不单独落盘 | 内嵌于 `{taskId}.json` 的 `checkpoints` 数组 | 只存最小恢复元信息,不存文件内容 |
| **Step** | ReAct Loop 中的一轮循环 | 单次 step 内 | ephemeral | 不进入任务系统,纯引擎内部单位 |

关键边界:任务系统管理的是 **SubTask 的状态机**,不是 Step 的循环。Step 失败/重试归 ReAct Loop 管;SubTask 失败由 LLM 或 orchestrator 决策(重试/拆分/跳过)。

### 8.3 状态机

```
PENDING ──► IN_PROGRESS ──► COMPLETED ──► (VERIFY 子任务) ──► VERIFIED
                  │              │
                  │              └─► (进入 VERIFY 失败) ──► IN_PROGRESS(修复) 或 FAILED
                  │
                  ├─► BLOCKED(依赖未满足) ──►(依赖解除)──► PENDING
                  │
                  └─► FAILED ──► (LLM 决策) ──► PENDING(重试) / SKIPPED
```

- **PENDING**:待开始,依赖未满足或在等待调度
- **IN_PROGRESS**:正在执行,绑定一个 `AgentTask` 跑 ReAct Loop
- **BLOCKED**:有未完成的依赖子任务,不可调度
- **COMPLETED**:子任务的 LLM 工作完成,但还未通过验证
- **FAILED**:执行失败,记录失败原因,等待 LLM 决策
- **SKIPPED**:被上游失败导致跳过(链式失败),不再执行
- **VERIFIED**:COMPLETED 后通过强制验证(mvn compile / mvn test / 启动验证)收尾,plan 唯一终态

转换约束:
- PENDING → IN_PROGRESS:必须所有依赖 COMPLETED+VERIFIED
- IN_PROGRESS → COMPLETED:由 LLM 显式调用 `complete_task` 触发,不能由 step 终止推断
- COMPLETED → VERIFIED:必须由 VERIFY 类型子任务执行,且验证脚本/命令返回 0
- IN_PROGRESS → FAILED(预算耗尽):step 预算用尽而 LLM 既没 `complete_subtask` 也没 `fail_subtask`,一律判 FAILED(`failureReason="step budget exhausted"`),**绝不因预算耗尽推断为 COMPLETED**——与"COMPLETED 必须显式触发"同源
- 任何 → FAILED:工具调用反复失败、LLM 显式放弃、验证连续 N 次不通过、或预算耗尽

### 8.4 依赖 DAG

- 显式声明:`dependsOn: [taskA, taskB]`
- 调度器在每个 SubTask COMPLETED+VERIFIED 时,扫描下游将其 PENDING → 可调度
- DAG 检测:创建 plan 时校验无环,有环直接拒绝创建
- v1 **串行执行**(同一 plan 内一次只跑一个 SubTask),简化调度与上下文管理
- 跨 SubTask 不复用上下文:每个 SubTask 启动一个全新的 `AgentTask`,独立 ephemeral,避免上一个子任务的临时观察污染当前子任务

### 8.5 持久化(JSON 落盘)

任务系统的存储不再与 part4 记忆系统同构,而是改用 JSON。原因:一个 SubTask 内嵌一个 `checkpoints` 数组,数组里每个元素又是一组结构化的恢复元信息(文件列表、函数列表),这种嵌套结构塞进 YAML frontmatter 会很别扭,而 JSON 天然适合嵌套数据,用 Jackson 直接序列化/反序列化即可。代价是可读性略降,但 `/tasks`、`/task <id>` 命令会把 JSON 渲染成人类可读的文本,不要求用户直接读 JSON。

```
.agent/
  tasks/
    {planId}/
      plan.json                  # 计划总览
      {taskId}.json              # 每子任务一个文件,内含 checkpoints 数组
      verify.log                 # 最后一次验证的原始输出
```

**plan.json** 字段:

- `planId` / `goal` / `createdAt` / `updatedAt` / `status`(active/completed/abandoned)
- `currentTaskId`(当前 IN_PROGRESS 的子任务) / `paused`(用户中途插入时置 true,orchestrator 暂停推进)
- `subtaskIds`(有序列表)
- `edges`(DAG 的依赖边,`{from: taskId, to: taskId}` 列表,与各 SubTask 的 `dependsOn` 冗余存一份,便于整体校验无环)

**{taskId}.json** 字段(围绕"这个子任务现在处于什么状态、做到哪了、接下来干什么"组织):

- `taskId` / `planId` / `title` / `type`(IMPLEMENT/ANALYZE/REFACTOR/VERIFY/FIX)
- `status` / `dependsOn`(依赖谁)
- `done`:完成了什么——已经落地的工作摘要(自然语言一段)
- `currentAction`:现在在干什么——当前正在进行的动作(IN_PROGRESS 时实时更新)
- `nextStep`:下一步是什么——LLM 规划的下一个动作,供中断恢复时接续
- `createdAt` / `startedAt` / `completedAt` / `attempts`(重试次数) / `failureReason`(FAILED 时填写)
- `artifacts`:产出物,本子任务改动过的文件路径列表
- `checkpoints`:恢复用的进度快照数组(见 §8.6)

**写盘一致性**:临时文件 + rename + fsync(与 part2/part4 的写盘策略一致)。`done` / `currentAction` / `nextStep` / `checkpoints` 是高频写,采用"读全文 → 反序列化为对象 → 改字段 → 写临时文件 → rename",避免锁竞争与半写文件。

**MEMORY.md** 增加任务索引条目:`{planId}/plan.json - 计划: 实现 X 功能`。LRU 20 自动覆盖,过期文件保留。

### 8.6 Checkpoint(任务恢复的最小锚点)

Checkpoint 是本次设计新增的概念,专门为**任务中断后恢复**而存在,和 part2 工具系统里的 `SideEffectTracker` 是两个不同层面的东西,不要混淆:

| | part2 `SideEffectTracker` | part5 Checkpoint |
|---|---|---|
| 存储 | 内存态(`InMemorySideEffectTracker`,按 executionId 分桶的栈) | 持久化,内嵌于 `{taskId}.json` |
| 存什么 | 文件修改前的完整字节 `preState` | 只存最小元信息,**不存文件内容** |
| 生命周期 | 单次 ReAct 执行,Loop 结束即 `clear()` | 随 SubTask 存活,跨重启/跨 session 可读 |
| 用途 | 工具调用**逻辑失败时立刻回滚**文件内容 | 记录"做到哪了",让恢复时知道从哪接续 |
| 粒度 | 每次文件写操作一条 | 每完成一个有意义的进度节点一条 |

**Checkpoint 只存纯元信息标记(最小集)**,每个 checkpoint 包含:

- `checkpointId`:序号或短 id
- `createdAt`:落盘时间
- `files`:本 checkpoint 涉及改动的文件路径列表
- `functions`:涉及的函数/方法名列表(如 `UserService.login`、`AuthFilter.doFilter`);**可选字段**,仅 LLM 主动 `save_checkpoint` 时填写,自动 checkpoint 一律留空
- `note`:一句话进度描述(如"已加完登录接口的参数校验")

**明确不存的东西**:不存文件内容、不存 diff、不存完整工具调用日志。这些要么能从磁盘现有文件读到,要么归 part2/执行记录管。Checkpoint 的职责边界就是"用最少的信息定位进度",真正的当前状态一律以磁盘上文件的现状为准。

**何时创建 checkpoint**:

1. 自动追加:一个 SubTask 内,每当一批修改文件的工具调用成功收尾,由 orchestrator 自动追加一条 checkpoint。自动 checkpoint **只填 `files`**(从工具调用的 `path` 参数聚合,可靠)与 `note`(用工具名兜底,如"edit_file × 3");`functions` 一律留空——文件类工具的参数里没有函数名这个信息,orchestrator 无法可靠提取,不为此额外上 AST/正则解析。
2. LLM 主动打点:LLM 在关键节点调用 `save_checkpoint`,此时才带上 `functions`(LLM 自己清楚在改哪个方法)与更精确的 `note`(见 §8.8)。

**恢复时如何用**:进程重启或 `/resume` 时,对每个 IN_PROGRESS 的 SubTask,读取它最后一条 checkpoint 的 `note` + `nextStep` 字段,拼进新 AgentTask 的初始上下文,告诉 LLM"上次做到这里,涉及这些文件和函数,下一步计划是 X";LLM 再自行 `read_file` 这些文件确认当前实际状态,不依赖 checkpoint 里的任何内容快照。

### 8.7 强制验证收尾(关键约束)

这是任务系统区别于"ReAct Loop 多跑几步"的本质。规则:

1. plan 创建时,LLM 必须在 `subtasks` 末尾追加一个 `type=VERIFY` 的子任务;若 LLM 漏加,orchestrator 在 `create_plan` 校验阶段自动补一个默认 VERIFY(仅当 plan 含代码修改类子任务时)
2. VERIFY 子任务的 `goal` 由 orchestrator 模板生成,模板根据项目类型自适应:
   - Java/Maven:`mvn -q compile && mvn -q test`
   - 检测到 `@SpringBootApplication`:`mvn -q compile && mvn -q test-compile`(见 verify-spring-runtime 反馈,启动验证留给用户)
3. VERIFY 子任务不可被 SKIPPED、不可被 LLM 标记 COMPLETED 跳过;必须由执行结果驱动状态机进入 VERIFIED
4. VERIFY 失败 → 自动创建一个 `type=FIX` 子任务并**局部改图**插到 VERIFY 之前:FIX 继承失败 VERIFY 原有的全部上游依赖,VERIFY 改为只依赖这个 FIX(即把 `... → VERIFY` 改写成 `... → FIX → VERIFY`),改动只触及这两个节点,写回 plan.json 的 `subtaskIds` 与 `edges`;FIX COMPLETED 后再次跑 VERIFY;连续 N 次(N=2)失败整个 plan 标记 abandoned
5. VERIFY 通过是 plan 从 active → completed 的唯一通路

验证命令的 stdout/stderr 完整落盘到 `verify.log`,便于用户事后翻查与 agent 在修复时回看。

### 8.8 与现有组件的衔接

**何时进入任务系统(agent 自主判断,不设开关也不设阈值)**:

- 不要求用户 `/plan on`、也不用机械的文件数阈值:接到请求时由 agent 自己判断该走单 Loop 直跑还是拆成 plan
- 判据是语义上的"改动会不会牵连多处",而非改了几个文件:成熟项目里即便只改一个文件,也常牵连调用方、测试、配置,这类应进任务系统;只有真正孤立的小改动(改文案、修一个明显 typo、纯问答)才单 Loop 直跑
- `/plan on` / `/plan off` 仅保留为**强制覆盖**开关,供用户在 agent 判断失误时手动纠偏,默认是 auto

**新增动态层 key**: `task_plan`。与 `messages` / `mid_term` 并列,不在消息流里,不被 messages 压缩影响,token 配额 1K。装配规则:

- 有 active plan → 注入 `task_plan`(当前 plan 摘要 + 当前 SubTask 标题)
- 无 active plan → 不注入
- plan 完成 → 转为中期记忆候选(由 part4 的轻量级 LLM 提取)

**新增工具**(LLM 在 ReAct Loop 内可主动调用):

- `create_plan(goal, subtasks[])` — 校验 DAG,创建 plan,移交 orchestrator 调度(拆解 Loop 随即退出)
- `start_subtask(taskId)` — PENDING → IN_PROGRESS,绑定新 AgentTask
- `complete_subtask(taskId, artifacts[], note?)` — IN_PROGRESS → COMPLETED
- `fail_subtask(taskId, reason)` — IN_PROGRESS → FAILED,记录原因
- `query_plan()` — 返回当前 plan 全量状态(给 LLM 决策下一步用)
- `skip_subtask(taskId, reason)` — FAILED 链式跳过下游
- `save_checkpoint(taskId, files[], functions[], note)` — 在关键进度节点追加一条 checkpoint,同时可顺带更新 `done` / `currentAction` / `nextStep`

**执行模型(方案 A:一次性拆解 + orchestrator 驱动)**:

- 拆解与执行分离:用户请求先进入一个"拆解 Loop",LLM 在其中调用 `create_plan` 产出 DAG 后,该 Loop 即退出,不再参与后续调度
- 之后由 `TaskOrchestrator` 按 DAG 拓扑序驱动:对每个可执行 SubTask 依次 `start_subtask`,各自启动一个**全新的 AgentTask**(独立 ephemeral、独立上下文),跑完推进状态机,再取下一个
- orchestrator 是唯一的调度主体,"外层拆解 Loop"不常驻;计划一旦产出即冻结,中途调整只能通过 §8.7 的 FIX 局部插入,不回到外层重新规划

**集成 ReAct Loop**:

- `LoopStepObserver` 在每次 step 结束时检查"当前 SubTask 是否已完成 LLM 工作"(检测 `complete_subtask` 工具调用),未完成则继续循环
- `AgentRuntimeImpl.run(task)` 完成时通知 task orchestrator:当前 SubTask 是否要进入 COMPLETED
- `AgentBudget` 允许 SubTask 级覆盖:单个 SubTask 可设更紧的 step 上限(如纯分析任务 5 步)

**新增事件**:

- `TaskPlanCreatedEvent`(planId)
- `SubTaskStartedEvent`(taskId, dependsOn)
- `SubTaskCompletedEvent`(taskId, attempts)
- `SubTaskFailedEvent`(taskId, reason)
- `VerifyStartedEvent` / `VerifyPassedEvent` / `VerifyFailedEvent`(exitCode, logPath)
- `PlanFinishedEvent`(planId, totalSubtasks, totalSteps)

**CLI 渲染**:

- 每次 step 后在状态行显示当前 plan 进度:`[plan] 实现用户登录  3/7  ✓解析需求  ✓设计API  ⋯编码`
- `/tasks` 命令列出当前 plan 全部 SubTask 与状态
- `/task <id>` 显示单个 SubTask 详情:`done` / `currentAction` / `nextStep`、产物、以及 checkpoint 列表(每条渲染成 `note` + 涉及文件/函数)
- `/verify` 手动触发当前 plan 的 VERIFY 子任务(默认自动跑)

### 8.9 失败与恢复

**失败的三层定义**:

- Step 失败:ReAct Loop 内的工具调用失败(归 part2 工具系统的 FailureKind 处理)
- SubTask 失败:LLM 在多次重试后仍认为无法完成,显式调用 `fail_subtask`
- Verify 失败:验证脚本返回非零,且自动修复两次仍未通过

**恢复机制**:

- 单 SubTask 失败:`attempts += 1`,LLM 在新 attempt 内可看到上次的 `failureReason` 与最后一条 checkpoint
- 预算耗尽的失败:重试时给一次性放宽的预算(1.5× step 上限);连续 2 次仍耗尽则该 SubTask 落 FAILED 终态,触发下游 SKIP / plan 部分完成,不再无限放宽
- 上游失败导致下游 BLOCKED 超时(默认 0 步,直接 SKIPPED):链式标记,plan 进入部分完成态
- 进程崩溃重启:启动时扫描 `.agent/tasks/*/plan.json`,若 status=active 且 currentTaskId 存在 IN_PROGRESS 记录,读取该 SubTask 的最后一条 checkpoint(§8.6),把 `note` / `files` / `functions` / `nextStep` 拼成恢复上下文,询问用户"恢复/放弃"
- 跨 session:plan 与 sessionId 绑定,session 结束冻结 plan(状态保留但不再自动推进);新 session 显式 `/resume <planId>` 恢复,恢复入口同样以最后一条 checkpoint 为锚点

**用户中途插入(串行单线程,不并存)**:

方案 A 是串行单线程,不存在"plan 后台跑、同时答问"的并存。用户在 plan 执行途中输入,一律先**暂停 plan**再处理:

- 暂停时机:当前 SubTask 跑到下一个 checkpoint 边界处挂起(orchestrator 停止推进,SubTask 保持 IN_PROGRESS,plan.json 记 `paused=true`);若用户显式打断(Esc),立即挂起并补落一条 checkpoint
- 分流处理,由 agent 判断插入内容属于哪类(复用 §8.8 的语义判断):
  - **无关提问**:当普通单 Loop 答完,orchestrator 自动 `resume`,plan 从挂起的 SubTask 续跑
  - **对计划的小调整**:因计划已冻结(方案 A),走 §8.7 的 FIX 式局部插入,不重排整个 DAG
  - **对计划的大改**:`abandon` 当前 plan,以新需求重新 `create_plan`

### 8.10 不做的事

明确边界,避免过度设计:

- v1 不做并行 SubTask 执行:同一个 plan 内串行,降低调度复杂度与上下文污染风险
- v1 不做自动 DAG 可视化:CLI 文本展示足够,/tasks 命令就是树形文本
- v1 不做任务模板库:LLM 自己拆解,模板留给 v2
- v1 不做跨 plan 依赖:plan 之间不互相引用,需要时合并成一个 plan
- v1 不做 SubTask 级 token 预算:每个 SubTask 独立 AgentTask,各自有 AgentBudget,共享全局上限

### 8.11 落地组件清单

| 类 | 职责 |
|---|---|
| `TaskPlan` | 计划聚合根,持有 DAG 与 SubTask 列表 |
| `SubTask` | 单个子任务,含状态机、`done`/`currentAction`/`nextStep` 与 checkpoints |
| `Checkpoint` | 恢复用的最小元信息记录(id/time/files/functions/note) |
| `SubTaskStatus`(enum) | PENDING/IN_PROGRESS/BLOCKED/COMPLETED/FAILED/SKIPPED/VERIFIED |
| `SubTaskType`(enum) | IMPLEMENT/ANALYZE/REFACTOR/VERIFY/FIX |
| `TaskPlanRepository` | plan.json / {taskId}.json 的读写,Jackson 序列化,临时文件+rename 落盘 |
| `TaskScheduler` | DAG 校验 + 下一个可执行 SubTask 选择 |
| `TaskOrchestrator` | 状态机转换 + 强制 VERIFY 收尾 + 自动修复插入 + checkpoint 自动追加 |
| `VerifyRunner` | 模板生成验证命令,执行,落盘 verify.log,判定通过/失败 |
| `TaskPlanContextKey` | 动态层 `task_plan` key 的装配器 |
| 工具类 7 个 | 见 §8.8,Spring AI `@Tool` 暴露给 LLM |
| 事件类 7 个 | 见 §8.8,沿用现有 AgentEvent 体系 |

### 8.12 与原方案的差异

- 新增"任务系统"作为独立子系统,与 ReAct Loop 解耦:Loop 只管"在 SubTask 内跑 step",orchestrator 管"SubTask 之间的状态推进"
- 引入强制 VERIFY 收尾:plan 完成必须通过真实执行验证,与 part2 的运行时验证反馈闭环
- 任务状态落盘为 JSON(用 Jackson):不再与 part4 记忆系统同构,原因是 SubTask 内嵌 checkpoints 数组这种嵌套结构更适合 JSON;写盘策略(临时文件+rename+fsync)与 part2/part4 一致
- 新增 Checkpoint 概念:持久化、任务级、只存最小恢复元信息(改动的文件/函数 + 一句话进度),与 part2 内存态、按文件内容回滚的 `SideEffectTracker` 分工明确、互补不重叠
- 是否进入任务系统由 agent 自主判断(不设手动阈值,`/plan on`/`off` 仅作强制覆盖):判据是"改动会不会牵连多处",成熟项目里单文件改动也常触发拆解;进入后创建真实 SubTask 并启动状态机,而非仅思考计划
- 失败不再只是 loop 内重试,而是上升到 SubTask 级 + 自动修复插入,把"留痕"做到跨步、跨重启可见
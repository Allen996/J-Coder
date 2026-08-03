## 6. 上下文工程

### 6.1 两层上下文（静态 + 动态）

上下文窗口本质只需要两层。**静态层**承载基本不变的"系统设定",**动态层**承载运行时累积的"对话与记忆"。两层之间用一个约定分隔符隔开,模型能识别边界但不影响内容连贯性。

```
┌─────────────────────────────────────────────────┐
│ Static Layer（启动 + /load 时刷新）              │
│   - 字典结构:多个独立 key                        │
│   - role / tools / code_cot / runtime_meta      │
│   大小:~4K tokens                               │
│ ───────────────────separator────────────────────│
│ Dynamic Layer（运行时累积）                       │
│   - 字典结构:多个独立 key                        │
│   - messages / mid_term / long_term / memory_index / ephemeral │
│   大小:剩余预算                                  │
└─────────────────────────────────────────────────┘
```

每个 layer 内部按 key 组织,每个 key 独立可改、可刷、可裁——修改一个 key 不触发其他 key 的重算或重渲染。每个 key 自带序列化通道:文本类进 SystemMessage 文本段,结构化类(如工具 schema)走 Spring AI 的 toolCallbacks 等专用选项,不全部压成同一段文本。

### 6.2 静态层（字典）

承载每次会话基本不变的内容,按 key 组织:

- `role_definition`:智能助手角色描述 → 文本通道
- `tool_list`:可用工具列表(自动从 @Tool 生成 schema) → 结构化通道(Spring AI toolCallbacks,不占文本预算)
- `code_writing_cot`:写代码的完整思维链 → 文本通道
- `runtime_meta`:当前时间、模型名、项目根路径 → 文本通道

加载时机:启动时一次性装配,`/load` 时刷新整个静态层(按 key 独立判断是否需要重新加载)。

修改策略:静态层整体视为只读;特定 key 可在显式触发下重写(如用户调整偏好、角色变更)。

### 6.3 动态层（字典）

承载运行时累积的内容,按 key 组织:

- `messages`:短期记忆原始流,user / assistant / tool-call 全量记录。Markdown 文件每条 message 一个段,含 role + 时间戳 + content;tool_call 与 tool_response **邻段配对**,共享同一 `message_id`;文件头部 YAML frontmatter 记录 schema 版本、sessionId、创建时间等元数据。**加载语义**:默认取最近 5 轮对话直接加入上下文;超预算则递减轮数(4→3→2→1);1 轮仍超则对该轮做 LLM 摘要压缩。不再使用滑窗淘汰。
- `mid_term`:中期记忆,**Session 整体总结**,由轻量级 LLM 在会话结束时生成,固定结构包含会话目标、已完成事项、关键决策、教训、后续待办。
- `long_term`:长期记忆,记录项目红线与编程风格约定,落盘为 `Nico.md`。由轻量级 LLM 在每轮对话结束后从对话中提取候选条目,经用户确认后写入。
- `memory_index`:记忆索引,即 `MEMORY.md` 内容,**常驻动态层但压缩阶段不被处理**。LRU 保留最近 20 个记忆条目,过期文件不删除(仅从索引移除)。
- `ephemeral`:临时观察事件流(thought / action / observation),每个 step 重建清理。单 step 内 2K token 硬上限,超额截断最旧观察。

加载时机:

- `messages`:ContextBuilder 装配时按"最近 5 轮递减"加载
- `mid_term`:由 `memory_index` 索引按相关性隐式匹配加载,默认返回最相关 5 条
- `long_term`:session 开始时从 `Nico.md` 全量加载进 prompt
- `memory_index`:session 开始时加载;记忆文件产生时同步更新;不存在时无需加载
- `ephemeral`:当前 step 写入,step 结束清理

修改策略:每个 key 完全独立——`memory_index` 永不压缩;`mid_term` 重新生成会整体替换;`long_term` 仅在用户确认候选条目后追加。

### 6.4 分隔符

静态层与动态层之间用一个约定标记隔开,例如:

- Markdown 水平线 `---`
- 或显式标签 `[DYNAMIC_START]`

分隔符不携带语义,仅作为模型对两层边界的视觉提示,便于在长上下文中定位"系统设定"与"对话内容"的分界点。

### 6.5 Token 预算

| 配额项 | 默认值 | 备注 |
|---|---|---|
| `contextWindowMax` | 128000 | 按模型动态调整 |
| `staticReserved` | 4000 | 静态层整体 |
| `dynamicReserved` | 剩余 | 动态层整体 |
| `memoryTokenReservation` | 4096 | completion 预留 |
| `maxSingleCallCompletion` | 4096 | 单次最大输出 |

动态层内部按 key 分配软配额:

- `messages`:由"最近 5 轮递减 + 单轮 LLM 压缩"控制,不设硬配额
- `mid_term`:固定小配额(默认 1K),超限触发整体重新生成
- `long_term`:固定小配额(默认 2K),超限触发按重要性重排
- `memory_index`:常驻,小配额(默认 0.5K,LRU 20 项),不被压缩
- `ephemeral`:不计入长期预算,每 step 重建;单 step 内 2K 硬上限,超额截断最旧观察

### 6.6 压缩策略

**静态层永不压缩**;`memory_index` 在压缩阶段不被处理。压缩只针对动态层其他 key:

1. **短期记忆加载压缩**——加载 messages 时默认取最近 5 轮;超预算则递减轮数(4→3→2→1);1 轮仍超则对该轮做 LLM 摘要压缩。
2. **清空 ephemeral**——每个 step 结束时丢弃全部临时观察事件,下个 step 重建。

`mid_term` 超额 → 触发整体重新生成(由轻量级 LLM 重新总结 session)。
`long_term` 超额 → 按重要性重排,旧条目可标 deprecated 但不删除。

### 6.7 项目结构读取

**ProjectScanner 不再作为启动期组件存在**。项目文件树、README、关键配置等不再预扫描注入静态层。

运行时需要时,通过工具(`scan_files` / `read_file` / `search`)按需查询——模型主动决定何时拉取项目信息,避免无用信息长期占预算。

### 6.8 与 part4 记忆系统衔接

三层记忆全部以 key 形式承载在动态层:

| 记忆层 | 动态层 key | 落盘位置 | 文件格式 |
|---|---|---|---|
| 短期 | `messages` | `.agent/sessions/{sessionId}/short-term.json` | JSON(Spring AI Message 序列) |
| 中期 | `mid_term` | `.agent/sessions/{sessionId}/mid-term.json` | JSON(四字段 + 可评分元数据) |
| 长期 | `long_term` | `NNN-<主题摘要>.md`(项目根,按主题多文件) | MD + YAML frontmatter |
| 索引 | `memory_index` | `MEMORY.md`(项目根) | MD + YAML frontmatter |

`MEMORY.md` 常驻动态层:`memory_index` key 在压缩阶段不被处理。它是**人类可读的目录页**,每行格式为 `- [{类型}] <记忆文件路径> — <简介>`;收录全部记忆文件,不做 LRU 淘汰(淘汰机制见 part4 §7.13,暂不实现)。

召回不再由索引行的关键词匹配决定,而是由 `MemoryRecallScorer` 按 part4 §7.7 的加权评分(词项重叠 + 重要度 + 时效 + 类型权重)对候选记忆打分,低于阈值不召回,**允许召回结果为空**。评分信号来自各记忆文件自身的 frontmatter / JSON 头部,不解析 `MEMORY.md`。`long_term` key 只常驻注入 `pinned` 与 `importance = 5` 的条目,其余长期条目按主题文件参与评分召回。

短期记忆的加载不再使用滑窗,改为"最近 5 轮递减 + 单轮 LLM 压缩"。占位符与同步落盘相关机制不再适用。session 重启时按 key 独立从磁盘恢复。

### 6.9 与原方案的差异

- 三层 (System/Project/Session) 简化为两层 (Static/Dynamic)
- ProjectScanner 移除,项目结构按需工具读取
- 每层从"扁平文本段"改为"字典式多 key",每个 key 独立可改
- 静态层不再承载项目约定,改为由动态层 `long_term` key 加载
- 记忆系统(part4)与上下文工程(part3)通过 key 名称对齐,无重复定义
- 上下文不再使用滑窗淘汰,改用"最近 5 轮递减 + LLM 单轮压缩"
- 短期/中期记忆改用 JSON 存储,长期记忆保留 MD + YAML frontmatter 并按主题拆分为多文件
- `MEMORY.md` 进入动态层作为 `memory_index` key,但不被压缩处理;降级为人类可读目录页,不再承载匹配逻辑
- 记忆召回改为加权评分 + 阈值门控,不相关时不注入(可为空),取代原先的关键词匹配与"匹配失败返回最近 N 条"
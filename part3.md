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
│   - messages / mid_term / long_term / ephemeral │
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

- `messages`:短期记忆原始流,user / assistant / tool-call 全量记录,带滑窗。每次 tool 调用完成后同步写盘(user 输入与最终 assistant 回复在产生时同步写盘);Markdown 文件每条 message 一个段,含 role + 时间戳 + content,tool_call 与 tool_response 同段或邻段配对。
- `mid_term`:中期记忆,per-session LLM 摘要。滑窗淘汰积攒到阈值后触发批量 LLM 摘要;会话结束触发整体重述。
- `long_term`:长期记忆,主要记录项目红线(禁止事项)与编程风格约定。由用户显式"记住"等指令触发写入,正常情况极少超出。
- `ephemeral`:临时观察事件流(thought / action / observation),step 结束清理。单 step 内 2K token 硬上限,超额截断最旧观察。

加载时机:

- `messages`:每次 tool 调用完成(或 user 输入 / 最终 assistant 回复产生)时同步落盘
- `mid_term`:session 开始或 `--resume` 时读入
- `long_term`:session 开始时全量加载或按相关性检索注入(由配置决定)
- `ephemeral`:当前 step 写入,step 结束清理

修改策略:每个 key 完全独立——刷新 `long_term` 不触发 `messages` 的滑窗重算,更新 `mid_term` 不影响 `ephemeral` 的临时观察。

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

- `messages`:主体预算,带滑窗,超额触发压缩
- `mid_term`:固定小配额(默认 1K),超限触发 key 自身重写
- `long_term`:固定小配额(默认 2K),超限触发按重要性重排
- `ephemeral`:不计入长期预算,每 step 重建;单 step 内 2K 硬上限,超额截断最旧观察

每个 key 独立跟踪 token 用量,改一个 key 不重算其他 key 的占用。

### 6.6 压缩策略

**静态层永不压缩**;压缩只针对动态层。按以下顺序分三步执行,每步独立判断是否触发:

1. **历史工具结果占位**——只保留最近 3 次 tool 调用的完整响应,更早的工具响应替换为占位符(如 `[tool result truncated, see mid-term]`)。assistant 消息中的 `tool_calls` 字段保留不动,仅替换对应 `ToolResponseMessage` 的响应内容。
2. **清空 ephemeral**——丢弃当前 step 的全部临时观察事件。
3. **压缩 messages**——滑窗淘汰最早条目进入待摘要队列;队列达到阈值后批量调用 LLM 摘要,结果追加进 `mid_term`。

`mid_term` 超额 → 触发"教训型片段"合并或重新生成。
`long_term` 超额 → 按重要性重排,旧条目可标 deprecated 但不删除。

每步独立判断是否执行,不必三步全跑;前一步未触发不阻塞后一步执行。

### 6.7 项目结构读取

**ProjectScanner 不再作为启动期组件存在**。项目文件树、README、关键配置等不再预扫描注入静态层。

运行时需要时,通过工具(`scan_files` / `read_file` / `search`)按需查询——模型主动决定何时拉取项目信息,避免无用信息长期占预算。

### 6.8 与 part4 记忆系统衔接

三层记忆全部以 key 形式承载在动态层:

| 记忆层 | 动态层 key | 落盘位置 |
|---|---|---|
| 短期 | `messages` | `.agent/sessions/{sessionId}/short-term.md` |
| 中期 | `mid_term` | `.agent/sessions/{sessionId}/mid-term.md` |
| 长期 | `long_term` | `Nico.md`(项目根) |

短期记忆(`messages` key)的同步写盘由 ContextBuilder 在每轮追加后触发,不再依赖内存中的临时缓冲兜底。session 重启时按 key 独立从磁盘恢复。

### 6.9 与原方案的差异

- 三层 (System/Project/Session) 简化为两层 (Static/Dynamic)
- ProjectScanner 移除,项目结构按需工具读取
- 每层从"扁平文本段"改为"字典式多 key",每个 key 独立可改
- 静态层不再承载项目约定,改为由动态层 `long_term` key 加载
- 记忆系统(part4)与上下文工程(part3)通过 key 名称对齐,无重复定义
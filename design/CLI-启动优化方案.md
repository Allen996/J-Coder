# CLI 启动场景与运行时可视化优化方案

> 目标：让 SuperBizAgent CLI 启动时一眼能看到关键信息，运行时有清晰的"我在干啥"反馈，整体更有人情味。

---

## 1. 现状（摸到的）

启动 Banner 现在的样子（`ReplLoop.printBanner()` 第 317-324 行）：

```
SuperBizAgent CLI · model=qwen3-max · session=xxx-yyy · type /help
[resume] N active plan(s) found on disk: ...
```

只告诉用户 model、session id，**没有**：
- 当前启动目录
- 任何 logo / 图标
- 任何"我在思考/我在调工具"的实时反馈

运行时的事件流（关键链路）：

| 阶段 | 事件 | 触发位置 |
|---|---|---|
| LLM 调用即将发起 | `onPromptBuilt(messages, stepIndex)` | `ReActLoop.subscribe()` 第 184 行，**已存在** |
| LLM 调用返回 | `onThought(ThoughtEvent)` | `ReActLoop` 第 212 行 |
| 工具调用前校验 | `onActionPreCheck` | `ToolGateway.invoke()` 第 134 行 |
| 工具调用真发起 | `onActionInvoked` | `ToolGateway.invoke()` 第 147 行 |
| 工具调用返回 | `onObservation` | `ToolGateway.invoke()` 第 162 / 260 行 |

也就是说 —— **钩子都在**，我们只要在 CliRenderer / ReplLoop 上订阅它们、画状态行就行。

现有渲染模型是「observer 把行入队 → REPL 主线程 drain」，要支持"每秒刷新的状态行"得在队外另搞一个状态行通道。

---

## 2. 方案分两块

### 块 A：启动 Banner 升级（一次性输出）

启动时一次性打印几行静态信息 + 一个 ASCII 图标，包含：
- 一个可爱的小图标
- 应用名 + 版本
- **当前启动目录**（这是用户新加的需求）
- **模型名 + 模型类型**（主对话模型 + memory 模型，因为 yml 里有两个）
- session id（保留）
- 提示快捷键

预计 5-8 行，纯静态，启动完就消失，不影响交互。

### 块 B：运行时状态行（实时更新）

模型"在做事"的窗口期，在 prompt 上方画一行**会动的状态行**，格式：

```
▌ Thinking(3.2s)            ← LLM 在生成
▌ Tool_calling(read_file, 0.8s)   ← 工具在执行
```

用户输入新命令前，这一行会一直刷新（每秒+1 次），等事件触发完毕就清掉。

实现关键是引入一个**独立于 renderQueue 的 status line**，由 REPL 主线程独占控制，避免和 observer 入队的内容打架。

---

## 3. Banner 设计

### 3.1 图标

ASCII art 小机器人（kawaii 风格），3-4 行高，用 ANSI 配色让它有立体感：

```
     ∧＿∧
   ( • ᴗ • )   ← 这就是"眼睛 + 嘴"的位置
   /  つ⊂  J-Agent
```
既是吉祥物又带名字，技术感不丢。颜色用 `YELLOW`/`CYAN`/`MAGENTA` 上色。

### 3.2 Banner 完整布局

```
[图标 4 行，可选 color]

SuperBizAgent CLI · v0.1.0
────────────────────────────────────────
  📂 启动目录  : D:\MyProgram\...SuperBizAgentV2
  🤖 对话模型  : qwen-plus (DashScope)
  🧠 记忆模型  : qwen-flash
  🆔 会话 ID   : 8f2e-xxxx (session-scoped, 重启即换)
  ⏱️ 启动时间  : 2026-07-29 14:32:11
────────────────────────────────────────
  输入 /help 看命令，/model 切模型，Ctrl-D 退出。
```

emoji 在 cmd.exe 下可能糊，但 Windows Terminal / VSCode / 现代终端都 OK；做兜底：探测不到 emoji 支持时降级为纯 ASCII 标签（如 `[DIR]` / `[MODEL]`）。

### 3.3 关键设计点

- **目录取绝对路径**：`Paths.get("").toAbsolutePath()`（ReplLoop 已经在用 projectRoot）
- **模型名**：
  - 对话模型：读 `spring.ai.dashscope.chat.options.model`（来自 yml；默认值 `qwen-plus`）
  - 记忆模型：读 `agent.memory.model`（默认 `qwen-flash`）
  - 两边都从 `ConfigurableEnvironment` 拿 —— 已有依赖，不用新加配置类
- **会话 ID**：复用 `SessionState.sessionId`
- **时间**：本地时区 `Instant.now()` → `LocalDateTime` 格式化

---

## 4. 状态行（Status Line）设计

### 4.1 触发与终止

| 状态 | 开始 | 结束 |
|---|---|---|
| Thinking | `onPromptBuilt(stepIndex=N)` | `onThought(ThoughtEvent)` / `onError` / `onFinish` |
| Tool_calling | `onActionInvoked(toolName)` | `onObservation(toolName)` / `onError` |

同一时刻只能有一种状态（LLM 不可能在调工具的同时还在思考），所以用一个枚举就够了：

```
IDLE → THINKING → IDLE → TOOL_CALLING → IDLE → THINKING → ...
```

异常路径（`onError` / `onLoopBudgetExceeded` / `onTokenBudgetExceeded`）也要把状态行清掉，避免卡片卡在屏幕上。

### 4.2 渲染策略：行内刷新

不依赖 JLine 的 AttributedString，用 ANSI 转义：
- 写状态行：`\r\033[K[内容]`（回行首 + 清行尾 + 内容）
- 状态变更时：清掉旧行 → 写新行
- REPL 主线程在 `drainTo(out, 100)` 的循环里**每 250ms** 重绘一次当前状态（秒数用 `Duration.between(startAt, now)` 实时算）

为什么 250ms：太频繁（如 50ms）会闪，太慢（如 1s）秒数跳动太突兀。250ms 在大部分终端下足够丝滑。

### 4.3 状态行的位置

策略选择：
- **A. 固定在 prompt 上方**（每次写状态行前先 clear，prompt 重新画）—— 干净
- **B. 固定在屏幕最底**（独立轨道） —— 实现复杂，要存光标位置
- **C. 在事件流输出之后再画** —— 用户能看到上下文，但状态行会和内容混在一起

**推荐 A**：状态行就是 prompt 的"上一行"。具体做法：
1. 在 readLine 之前，把状态行写到当前行
2. 用户回车后，输出 \n 进入下一行
3. 渲染事件时也是正常 println
4. 状态行需要更新时，先 `\033[1A\033[K` 回到状态行那行 → 清 → 重写 → 再 `\033[1B` 回到原位

JLine 在 readLine 的时候会把光标放在 prompt 末尾，我们要保证状态行的输出在 JLine 不抢字的前提下进行 —— 简单做法是**状态行只在 REPL 主线程被唤醒时更新**（沿用现有 poll 循环），不阻塞 readLine。

### 4.4 状态行格式

```
▌ Thinking...(3.2s)              ← 青色，italic
▌ Tool_calling...(read_file, 0.8s)   ← 黄色
```

颜色：
- Thinking → `CYAN`（安静、专注）
- Tool_calling → `YELLOW`（在"做事"）
- 异常态 → `RED`（比如 "ERROR" 短暂出现）

数字格式：
- < 60s：`3.2s`（1 位小数）
- ≥ 60s：`1m05s`（分秒）

工具名长度限制：超过 30 字符截断 + `…`。

---

## 5. 代码改动清单

### 新增文件
- `src/main/java/org/example/cli/renderer/StatusLine.java`
  - 单例组件，封装状态枚举、当前阶段、开始时间、当前渲染内容
  - 方法：`begin(Phase, label)`、`finish()`、`render(elapsed)` 返回 ANSI 字符串
  - 不直接写 PrintWriter，由调用方拿到字符串后写

### 修改文件
- `src/main/java/org/example/cli/renderer/AnsiStyle.java`
  - 新增颜色：`ITALIC_CYAN`（如果有终端支持）、`RED_BOLD`
  - 新增工具方法：`elapsedFormat(Duration)` 返回 `3.2s` / `1m05s`

- `src/main/java/org/example/cli/repl/ReplLoop.java`
  - `printBanner()` 重写：调用新组件 `StartupBanner.print(out, env, session, projectRoot)`
  - 注入 `StatusLine` 和新增的 `StartupBanner`
  - `dispatchAgent()` 主循环里增加：在每次 `renderer.drainTo` 之前先尝试刷新状态行
  - `printResumablePlans()` 逻辑保留

- `src/main/java/org/example/cli/renderer/CliRenderer.java`
  - 实现 `onPromptBuilt(...)`：调 `statusLine.begin(THINKING, null)`
  - `onActionInvoked(...)`：调 `statusLine.begin(TOOL_CALLING, toolName)`
  - `onObservation(...)`：调 `statusLine.finish()`
  - `onThought(...)`：调 `statusLine.finish()`
  - `onFinish(...)` / `onError(...)`：调 `statusLine.finish()`

### 新增文件
- `src/main/java/org/example/cli/renderer/StartupBanner.java`
  - `@Component`，构造注入 `ConfigurableEnvironment`、`SessionState`、`Path projectRoot`
  - `print(PrintWriter)`：打 ASCII 图标 + 5-8 行信息

### 可选
- `src/main/java/org/example/cli/renderer/EmojiSupport.java`
  - 探测 stdout 编码 / TERM / 是否 Windows Terminal，决定是否启用 emoji
  - 简单实现：Windows + 非 WT 时退化

---

## 6. 风险点 / 取舍

1. **状态行和 JLine 的 readLine 抢光标**
   - 现状：JLine readLine 占用整行编辑，状态行必须在 readLine **之前/之外**写
   - 解法：状态行只由 REPL 主线程在 poll 间隙写；不试图在 readLine 阻塞期间刷新秒数
   - 影响：状态行的"每秒+1"实际是"每次 poll +1"，通常 < 250ms 误差，肉眼看不出

2. **dumb terminal / cmd.exe 不支持 ANSI**
   - 复用 AnsiStyle 现有策略 —— 没有 ANSI 时颜色消失、CR 控制符失效
   - 终端探测可后续加，v1 先假设支持 ANSI（项目已有这个假设）

3. **多个工具并发调用**（`parallel=true` 时）
   - 状态行只能显示一个 tool name —— 显示**第一个**开始的，结束也按**最后一个**结束算
   - 或者：状态行只显示 "Tool_calling(N calls, ...)" 汇总
   - v1 简化：只显示第一个工具名

4. **图标是否过于花哨**
   - 静态 banner 多 4 行 ASCII —— 启动信息变长，可以接受
   - 若觉得多余，可加 `/quiet` slash 命令关闭 banner

5. **状态行和已有事件输出的顺序**
   - 现状：onActionPreCheck 打 "⚙ xxx(args)" → onActionInvoked 打 "⚙ xxx → invoked" → onObservation 打输出
   - 加状态行后：pre_check 仍然照打（说明要调啥），然后状态行出现 "Tool_calling(xxx, ...)"，observation 时状态行消失并打输出
   - 也就是说**状态行取代了 "→ invoked" 那行的位置**，可以删掉 onActionInvoked 原本的打印，避免重复

---

## 7. 测试计划

- `StartupBannerTest`：注入 mock env + session + path，断言 print 输出包含目录 / 模型 / 图标关键字
- `StatusLineTest`：
  - `begin(THINKING)` → `render(elapsed=0)` → 输出 "Thinking(0.0s)"
  - `begin(TOOL_CALLING, "read_file")` → `render(elapsed=1500ms)` → "Tool_calling(read_file, 1.5s)"
  - `finish()` → `render()` → 空串
  - 状态切换：THINKING → finish → TOOL_CALLING → finish → 互不污染
- 手工测试（dev 时跑 Main）：
  - 启动看 banner 是否清晰
  - 故意问一个需要调工具的问题，观察状态行是否出现 + 秒数是否递增 + 完成后是否消失
  
  - 把模型调成慢的（qwen-max）观察 Thinking 行是否平滑

---

## 8. 实施顺序

1. `AnsiStyle` 加工具方法（最小改动）
2. `StartupBanner` 写完 + 单测 + 替换 `printBanner()`
3. `StatusLine` 写完 + 单测
4. `CliRenderer` 接入 `StatusLine`，联动 onPromptBuilt / onThought / onActionInvoked / onObservation
5. `ReplLoop.dispatchAgent` 主循环挂上状态行刷新
6. 端到端跑通，调样式 / 调时间粒度

每步独立可回退。
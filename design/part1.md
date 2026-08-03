## 4. CLI REPL

### 4.1 主循环

用 **JLine 3** 做终端 IO，**Picocli** 做命令解析：

```
┌────────────────────────────────────────────────────────────┐
│  JLine 读取一行                                            │
│    │                                                       │
│    ├─ 以 `/` 开头  → SlashCommandRegistry 派发            │
│    ├─ 以 `@` 开头  → @file 注入，剩余部分作为用户输入     │
│    ├─ 以 `!` 开头  → 本地 shell 简单透传（受限白名单）    │
│    └─ 其他         → 构造 AgentTask，交给 AgentRuntime    │
└────────────────────────────────────────────────────────────┘
```

### 4.2 Slash 命令全集（v1）

| 命令 | 作用 |
|---|---|
| `/help` | 列出所有命令 |
| `/clear` | 清空当前会话消息（保留 session_id） |
| `/compact` | 手动触发上下文压缩 |
| `/cost` | 显示当前会话 token 消耗 + 估算成本 |
| `/model <name>` | 切换模型（qwen3-max / qwen-coder-plus / ...） |
| `/init` | 生成 `CLAUDE.md` 项目配置（首跑时） |
| `/plan [on\|off]` | 进入/退出计划模式 |
| `/auto` | 一次性批准所有工具调用（不推荐长期开） |
| `/diff` | 显示本次会话所有写操作 diff |
| `/undo` | 撤销最后一次写操作（基于 git 暂存） |
| `/resume [id]` | 恢复历史会话；不传 id 则交互选择 |
| `/export <path>` | 导出当前会话为 JSONL |
| `/mcp` | 列出已加载的 MCP 工具 |
| `/verbose` | 切换 verbose 模式（显示完整 prompt + tool IO） |
| `/exit` | 退出（自动持久化） |

### 4.3 终端渲染

`CliRenderer` 把 `AgentEvent` 流渲染为带 ANSI 颜色的文本：

| 事件 | 样式 | 示例 |
|---|---|---|
| `ThoughtEvent` | 灰色 + 折叠（Enter 展开） | `▸ 思考：用户想读 X 文件...` |
| `ActionPreCheckEvent` | 黄色 | `⚙ run_shell("mvn test")` |
| `ActionInvokedEvent` | 黄色加粗 | `⚙ run_shell → 200 OK (2.3s)` |
| `ObservationEvent` | 白色（超 100 字符折叠） | `⎡ 输出 ⎦  14 lines` |
| `TokenBudgetEvent` | 黄色 | `⚠ token 80% used` |
| `LoopBudgetEvent` | 黄色 | `⚠ step 12/12` |
| `FinishEvent` | 绿色（FINISH）/ 红色（ERROR） | `✓ 完成 (12 steps, 2340 tokens)` |
| 用户授权请求 | 黄色高亮 + y/n/all 提示 | `? 批准 run_shell? [y/n/all]:` |

### 4.4 输入增强

- **`@file` 引用**：`@src/main/java/Foo.java` 解析为 `(path, content)` 注入到用户消息。
  - 多文件：`@a.java @b.java 解释这两个文件的关系`
  - 自动截断：单文件 > 50KB 时只取首尾各 200 行
- **历史命令**：JLine 内置，上下方向键浏览
- **多行输入**：未闭合引号 / 反引号 / 大括号 → 进入多行模式，`"""` 收尾

---
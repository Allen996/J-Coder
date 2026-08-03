## 5. 工具系统

### 5.1 工具集（M1 必交付）

| 类 | 工具 | 风险 | 需授权 |
|---|---|---|---|
| **File** | `read_file(path, startLine?, endLine?)` | 低 | ❌ |
| | `write_file(path, content)` | 中 | ✅ |
| | `edit_file(path, oldText, newText)` | 中 | ✅ |
| | `list_dir(path, depth?)` | 低 | ❌ |
| | `glob_files(pattern, path?)` | 低 | ❌ |
| **Shell** | `run_shell(command, timeout?)` | 高 | ✅ |
| | `check_command_exists(name)` | 低 | ❌ |
| **Grep** | `grep(pattern, path, glob?, ignoreCase?)` | 低 | ❌ |
| **Git** | `git_status()` | 低 | ❌ |
| | `git_diff(file?, staged?)` | 低 | ❌ |
| | `git_log(n?)` | 低 | ❌ |
| | `git_commit(message, addAll?)` | 中 | ✅ |
| | `git_show(ref)` | 低 | ❌ |

### 5.2 工具注册（Spring AI `@Tool` 注解）

```java
@Component
public class FileTools {

    private final ProjectRoot projectRoot;

    @Tool(description = "读取文件指定行范围。startLine/endLine 都是 1-based，包含两端。")
    public ReadResult readFile(
            @ToolParam(description = "绝对路径或项目相对路径") String path,
            @ToolParam(description = "起始行（包含），缺省从第 1 行开始", required = false) Integer startLine,
            @ToolParam(description = "结束行（包含），缺省到最后一行", required = false) Integer endLine) {
        // 1. 路径闸校验（见 5.3）
        // 2. 读取 + 行切片
        // 3. 超长文件返回 ReadResult.truncated(meta, preview, hint="use startLine/endLine")
    }
}
```

**关键点**：
- 每个工具返回结构化结果（不是裸 String），便于 CliRenderer 折叠展示
- 工具实现里**不**做 LLM 调用——纯本地 IO
- 工具异常包装成 `ToolExecutionException` 返回，LLM 拿到的是"执行失败 + 原因"而不是 stacktrace
- 完整返回契约见 5.6.4（`ToolResult` / `ToolError` / `ToolErrorCode`）

### 5.3 沙箱（Authorizer + Sandbox 三道闸）

#### 闸 1：路径闸（所有文件类工具）

```
白名单：项目根目录 + ~/.local-cli-copilot/trusted-paths.json 配置的额外目录
黑名单（硬编码）：
  - ~/.ssh / ~/.aws / ~/.kube / ~/.docker
  - /etc / /proc / /sys / /dev
  - 包含 shell 特殊字符的路径（防注入）
处理：
  1. 软链解析 → realpath
  2. realpath 是否在白名单内（祖先匹配）
  3. 是否在黑名单内（精确匹配 + 前缀匹配）
  4. 任一不通过 → ToolDeniedException
```

#### 闸 2：命令闸（仅 shell 类工具）

```
黑名单 pattern（正则）：
  rm\s+(-[a-zA-Z]*[rfRF][a-zA-Z]*\s+)*[/~]    # rm -rf /
  mkfs
  dd\s+if=
  :\(\)\s*\{.*\};:\s*                          # fork bomb
  >\s*/dev/sd[a-z]
  chmod\s+(-R\s+)?777\s+/
  curl\s+.*\|\s*(bash|sh)\s*                  # curl | sh

白名单（命令首词在这些里面时，跳过授权但仍走闸 3）：
  ls cat head tail wc file stat find grep rg tree du df
  git gh mvn gradle npm pnpm yarn cargo go python pip pytest
  docker docker-compose kubectl

其他命令 → 走用户授权（5.4）
```

#### 闸 3：执行闸（仅 shell）

- 默认超时 30s；调用方可通过 `timeout` 参数覆盖（上限 10min）
- stdout/stderr 各限 100KB，超限截断 + 提示
- 工作目录强制项目根
- 并发限制：最多 4 个并行 shell（防止 fork bomb）
- 环境变量：白名单传递（PATH, HOME, LANG, TZ 等），其余清空

### 5.4 用户授权 UI

危险工具首次调用前弹出确认：

```
? 批准 run_shell?  (project=my-app, dir=src/main/java)

  $ mvn test -Dtest=FooTest

  [y]  批准本次
  [n]  拒绝
  [a]  本会话同类工具全部自动批准
  [?]  查看完整命令详情
```

- `/auto` 一次性全部批准
- `/auto -r` 撤销自动批准（恢复按次确认）
- 授权决策记入 `tool_invocation.authorized` 字段

### 5.5 工具调用可观测

每次工具调用记一条结构化日志 + SQLite 记录：

```json
{
  "ts": "2026-07-09T10:23:45.123+08:00",
  "event": "tool_invocation",
  "session_id": "...",
  "tool": "run_shell",
  "args": {"command": "mvn test"},
  "status": "ok",
  "duration_ms": 2341,
  "authorized": true,
  "result_redacted": "Tests run: 14, Failures: 0"
}
```

### 5.6 工具调用失败处理（3 类 + 各自策略）

> 本节背景讨论见对话记录：从"防死循环"收敛到"只对失败重试"，再到"失败必须分类"。
> 关键决策：**只重试瞬时失败，参数错误反馈给 LLM 不重试，逻辑错误看是否可回滚。**
> 原因：把 retry 写成"失败就再来一次"会复现确定性失败造成的死循环（bad path → 重试 → 再 bad path → 再重试……），
> 而且吞掉 LLM 应该看到的"参数错了"信号。

#### 5.6.1 失败分类（`FailureKind`）

```
FailureKind (enum)
 ├─ TRANSIENT   瞬时：网络超时 / socket 异常 / 5xx HTTP / 429 rate limit
 ├─ PARAM       参数：bad path / 非法参数 / ToolDeniedException（沙箱拒绝）
 └─ LOGIC       逻辑：工具执行成功但结果不符预期（grep 0 行 / mvn test fail / shell exit != 0）
```

**判定规则**（`FailureClassifier.classify(Throwable, ToolDescriptor)`）：

| 异常 / 信号 | FailureKind | 备注 |
|---|---|---|
| `SocketTimeoutException` / `ConnectException` | TRANSIENT | 真瞬时，可重试 |
| `IOException`（其他子类） | TRANSIENT | 大多数 IO 错误可重试 |
| `InterruptedException` / `TimeoutException` | TRANSIENT | 工具执行闸超时，重试 |
| `ToolDeniedException` | PARAM | 沙箱拒绝，**不重试**，告知 LLM 调整路径/命令 |
| `IllegalArgumentException` | PARAM | 参数非法，**不重试** |
| `ToolExecutionException`（携带 errorCode） | 视 errorCode 而定 | 见 5.6.4 |
| shell exit code != 0 | LOGIC | 工具本身跑通但语义失败 |
| 其他 `RuntimeException` | LOGIC | 兜底为逻辑错误 |

> **关键约束**：FailureKind 判定只看 Throwable 类型 + tool 返回的 status code，**不看错误消息字符串**。
> 字符串匹配是反模式（i18n / 改一个 log 文案就破）。

#### 5.6.2 策略矩阵

| FailureKind | 是否自动重试 | 后续动作 | LLM 收到的反馈 |
|---|---|---|---|
| **TRANSIENT** | ✅ 是 | 退避后重试 | 重试耗尽后给 `ToolResult.error(TRANSIENT, "重试 N 次仍失败")` |
| **PARAM** | ❌ 否 | 立刻返回 | `ToolResult.error(PARAM, errorCode, suggestion)`，让 LLM 下一轮修正 |
| **LOGIC** | ❌ 否 | 看 tool 是否可逆 | 可逆 tool 触发 `SideEffectTracker.rollback()`；不可逆 tool 给 LLM 失败结果，让 LLM 决定下一步 |

#### 5.6.3 重试策略（`RetryPolicy`）

默认配置：

```
maxAttempts    = 3                  # 含首次，总共 3 次
backoffMillis  = [500, 1000, 2000]  # 第二次起的等待
sameArgs       = true               # 重试用同 args
appliesTo      = [TRANSIENT]        # 只对 TRANSIENT 触发
```

**为什么用同 args**：瞬时失败的本质是"调用本身没问题，是环境抖动"。改 args 反而掩盖问题，
让 LLM 误以为是参数导致的失败。

**退避算法**：固定指数序列 `[500, 1000, 2000]ms`。`2^n * 500`。不引入 jitter——v1 范围里
单任务并发 shell 上限 4，jitter 收益小、实现复杂度高。

**重试与步数预算的关系**：每次重试**消耗 step 预算**（1 次重试算 1 个 step）。
这避免了"重试 N 次绕过 step 上限"的漏洞。如果 3 次重试 + 首次共 4 次用完，
已经接近 12 步上限的 1/3，LLM 会被 LoopStepObserver 强 FINISH。

#### 5.6.4 错误返回契约（`ToolResult` / `ToolError`）

工具**永远**返回 `ToolResult`，从不抛异常穿透到 LLM（`ToolDeniedException` 是个例外，
它被 `ToolGateway` 拦截并翻译成 `ToolResult.error`，不让 Spring AI 框架看到）。

```java
public record ToolResult(
    Status status,          // OK | ERROR | DENIED
    String content,         // 成功时：脱敏后的明文/结构化结果
    ToolError error         // 失败时：errorCode + message + suggestion
) {
    public enum Status { OK, ERROR, DENIED }
}

public record ToolError(
    FailureKind kind,       // TRANSIENT / PARAM / LOGIC
    String errorCode,       // 机器可读：PATH_NOT_FOUND / SANDBOX_DENIED / TIMEOUT ...
    String message,         // 给人看的
    String suggestion       // 怎么修（给 LLM 看的提示）
) {}
```

`errorCode` 取值约束（封闭枚举，定义在 `ToolErrorCode`）：

```
PATH_NOT_FOUND              PARAM    路径不存在
PATH_OUTSIDE_SANDBOX        PARAM    沙箱拒绝（白名单外）
PATH_BLACKLISTED            PARAM    沙箱拒绝（黑名单内）
SHELL_DENIED                PARAM    命令正则黑名单命中
SHELL_ARG_INJECTION         PARAM    shell 参数含注入字符
TIMEOUT                     TRANSIENT 闸 3 执行超时
NETWORK_TRANSIENT           TRANSIENT 底层 IO 瞬时失败
COMMAND_NOT_FOUND           PARAM    闸 2 shell 找不到命令
SHELL_NONZERO_EXIT          LOGIC    shell exit != 0（语义失败）
GREP_ZERO_HITS              LOGIC    grep 没命中（语义失败）
WRITE_FILE_CONFLICT         PARAM    edit_file 的 oldText 不匹配
GIT_COMMIT_NO_CHANGES       LOGIC    git_commit 没有 staged
```

#### 5.6.5 逻辑错误的回滚（v1 简化版）

`LOGIC` 类失败里"可逆"的部分需要回滚，v1 的最小可用版：

- **文件类**（write_file / edit_file）：`ToolDescriptor.reversible = true`，失败时由
  `SideEffectTracker.rollback()` 删文件或 `git checkout -- <path>`。v1 不实现持久化的
  SideEffectTracker（每次任务 in-memory 即可），v2 持久化到 SQLite。
- **shell 类**（run_shell）：默认 `reversible = false`。即使本地可逆（`mvn clean`），
  也可能产生外部副作用（`mvn deploy`）。v1 不自动回滚，把失败结果给 LLM，让 LLM 决定。
- **`/undo` 命令**：v1 仅对"任务内最后一次成功的写操作"生效，依赖 in-memory 栈。

#### 5.6.6 落地组件

| 类 | 职责 |
|---|---|
| `FailureKind` (enum) | 失败分类枚举 |
| `ToolErrorCode` (enum) | 错误码封闭集合 |
| `FailureClassifier` | `classify(Throwable, ToolDescriptor) → FailureKind` |
| `RetryPolicy` | 重试策略封装（maxAttempts / backoff / appliesTo） |
| `ToolResult` / `ToolError` | 工具返回值契约 |
| `ToolExecutionException` | 工具内部抛出的异常（带 errorCode + suggestion） |
| `ToolDeniedException` | 沙箱拒绝专用异常（被 ToolGateway 拦截翻译） |
| `SideEffectTracker` (v1 in-memory) | 记录可逆 tool 调用，失败时回滚 |

#### 5.6.7 与现有架构的接缝

- `FailureClassifier` 是纯函数，无状态，注入到 `ToolGateway` 即可。
- `RetryPolicy` 通过 `AgentBudget` 接受配置覆盖（任务级 budget 可下调 `maxAttempts`）。
- `SideEffectTracker` 挂在 `AgentTask` 生命周期上，task 结束自动清空。
- `ToolGateway` 是 `@Component`，单例注入到 `ReActLoop`（ReActLoop 改造时拿到 gateway 引用）。

---
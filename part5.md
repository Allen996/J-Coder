## 8. Plan 模式

### 8.1 触发

- 显式：`/plan on`
- 隐式：用户输入命中"先规划/先分析/不直接改"语义（启发式）
- 自动：检测到任务涉及修改 ≥ 3 个文件

### 8.2 行为

进入 Plan 模式后：
- 所有写工具被 `PlanModeObserver` 拦截：`write_file` / `edit_file` / `run_shell`（写性命令）
- 只读工具照常工作：`read_file` / `list_dir` / `grep` / `git_status`
- Agent 输出结构化计划（Markdown 格式）

计划模板：
```markdown
## 计划：<任务摘要>

### 步骤
1. [ ] 读 src/main/java/Foo.java 了解当前实现
2. [ ] 修改 Bar 类的 X 方法（编辑 5 行）
3. [ ] 新增 BazTest 单元测试

### 影响文件
- src/main/java/.../Foo.java (edit)
- src/main/java/.../Bar.java (edit)
- src/test/java/.../BazTest.java (new)

### 风险
- Bar.java 修改可能影响 3 个调用方，需回归测试

### 验证
- mvn test
```

### 8.3 用户审阅

```
? Plan Mode: 接受 / 拒绝 / 修改

  [a] accept  — 继续执行计划
  [r] reject  — 放弃本次任务
  [e] edit    — 修改计划（输入反馈后让 agent 重新规划）
```

### 8.4 实现

`PlanModeObserver implements ReActLoopObserver`：

```java
@Override
public void onActionPreCheck(ActionPreCheckEvent event, ReActLoopSignal signal) {
    if (planMode && WRITE_TOOLS.contains(event.getToolName())) {
        event.setStatus(ActionStatus.DENIED);
        event.setDenialReason("Plan mode: write operations are blocked. " +
                              "Output a structured plan and ask user to /plan accept.");
    }
}
```

通过 signal 告知 agent，让它改输出计划而不是继续尝试。

---

## 9. 子 Agent（Task 工具）

### 9.1 目的

主 Agent 派发独立子 Agent 处理**复杂子任务**，避免：
- 主上下文被长 tool IO 污染
- 中途切换 focus 丢失主线

### 9.2 与主 Agent 的区别

| 维度 | 主 Agent | 子 Agent |
|---|---|---|
| system prompt | 通用助手 | 角色化（"你是 X 角色，专做 Y"） |
| 工具集 | 全量 | 裁剪（默认不含写工具） |
| 上下文 | 整个会话 | 独立窗口 |
| 输出 | 完整交互 | **摘要**（≤500 tokens） |
| 持久化 | 写入主 session | 仅记入 tool_invocation |
| 嵌套 | 可派生子 Agent | v1 不允许嵌套 |

### 9.3 Task 工具签名

```java
@Tool(description = """
    派发一个独立子 Agent 处理复杂子任务。
    子 Agent 拥有独立上下文，结果以摘要形式返回，不会污染主会话历史。
    适用场景：长篇代码探索、批量分析、独立子任务。
    """)
public String task(
    @ToolParam(description = "子任务描述，要具体且自包含") String prompt,
    @ToolParam(description = "子 Agent 角色，如 'code-explorer' / 'test-writer'", required = false) String role,
    @ToolParam(description = "子 Agent 可用工具 ID 列表，逗号分隔；缺省用默认只读集", required = false) String tools
);
```

### 9.4 内置子 Agent 角色（v1）

| role | system prompt 摘要 | 默认工具 |
|---|---|---|
| `code-explorer` | "你是代码探索专家，只读不写" | read_file, list_dir, glob_files, grep, git_log |
| `test-writer` | "你是测试编写专家，先读实现再写测试" | 全量（含 write_file，但每次写都需授权） |
| `doc-writer` | "你是文档撰写专家" | read_file, list_dir, grep, write_file, edit_file |

### 9.5 实现

```java
@Component
public class TaskTools {
    private final AgentRuntime runtime;
    private final SubAgentFactory factory;

    public String task(String prompt, String role, String tools) {
        AgentTask subTask = factory.buildSubTask(prompt, role, tools);
        AgentExecutionResult result = runtime.execute(subTask);
        return result.getFinalAnswer();  // 已经过摘要压缩
    }
}
```

`SubAgentFactory` 负责：
- 选择 system prompt（按 role）
- 裁剪 tool 列表（注入到 SpringAiReactAgentProvider）
- 设置独立 budget（maxSteps=8, maxTokens=20K）

---

## 10. MCP 集成

### 10.1 配置文件

`~/.local-cli-copilot/mcp.json`：

```json
{
  "mcpServers": {
    "filesystem-extra": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/extras"]
    },
    "github": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"],
      "env": { "GITHUB_TOKEN": "${env:GITHUB_TOKEN}" }
    },
    "playwright": {
      "command": "npx",
      "args": ["-y", "@playwright/mcp@latest"]
    }
  }
}
```

支持 `${env:VAR}` 引用环境变量（启动时展开）。

### 10.2 Spring AI 集成（关键 pom 调整）

⚠ **当前 pom 用的是 `spring-ai-starter-mcp-client-webflux`，CLI 场景应替换为 stdio 版**：

```xml
<!-- 替换这个 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client-webflux</artifactId>
</dependency>

<!-- 改成这个 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

理由：
- CLI 场景下 MCP server 大多是 stdio 模式（npx / uvx / 二进制）
- stdio 版用同步阻塞 IO 模型，更适合 CLI 的单线程交互
- WebFlux 版会引入 webflux 依赖，与"无 Web"原则冲突

### 10.3 启动期装配

```java
@Configuration
public class McpConfig {

    @Bean
    public McpClient mcpClient(@Value("${agent.mcp-config}") String configPath) {
        McpConfigFile config = McpConfigFile.load(Path.of(configPath));
        List<McpServer> servers = config.getServers().stream()
                .map(this::spawnStdioServer)
                .toList();
        return McpClient.builder()
                .servers(servers)
                .build();
    }

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(McpClient mcpClient) {
        // Spring AI 官方提供的桥接
        return new SyncMcpToolCallbackProvider(mcpClient);
    }
}
```

`SpringAiReactAgentProvider` 已经自动注入 `ToolCallbackProvider`，MCP 工具会自动并入 agent 的工具列表（无需额外改动）。

### 10.4 MCP 工具的权限统一

MCP 工具与本地工具走同一套 Authorizer：
- 工具描述里声明风险等级（通过 `@Tool` 的 description 后缀约定：`[RISK:HIGH]`）
- `/mcp` 命令列出当前所有 MCP 工具及其风险
- 危险 MCP 工具仍走用户授权

---

## 11. 可观测

### 11.1 结构化日志

每次启动一个 session，写一个 JSONL 文件：`~/.local-cli-copilot/logs/<session_id>.jsonl`

```jsonl
{"ts": "...", "event": "session_start", "model": "qwen3-max", "project": "/Users/me/my-app"}
{"ts": "...", "event": "user_input", "content": "帮我重构 Foo 类"}
{"ts": "...", "event": "agent_thought", "summary": "需要先读 Foo.java 了解结构"}
{"ts": "...", "event": "tool_call", "tool": "read_file", "args": {"path": "src/.../Foo.java"}}
{"ts": "...", "event": "tool_result", "tool": "read_file", "duration_ms": 12, "status": "ok"}
{"ts": "...", "event": "tool_call", "tool": "edit_file", "args": {...}, "authorized": true}
{"ts": "...", "event": "session_end", "total_tokens_in": 1234, "total_tokens_out": 567}
```

### 11.2 调试模式

`agent --debug`：
- 打印每次 LLM 调用的完整 prompt（system + tools + history）
- 打印每次 tool 调用的 args + raw result
- 打印 token 用量每步
- 输出到 stderr，不影响 REPL

### 11.3 成本面板

`/cost` 显示：
```
Session: abc-123 (running 15min)
  Tokens in:  12,340
  Tokens out:  5,678
  Estimated cost (DashScope qwen3-max): ¥0.42
```

---

## 12. 启动与打包

### 12.1 启动方式

```bash
# 开发模式
mvn spring-boot:run

# 打包（fat jar）
mvn clean package
java -jar target/super-biz-agent.jar

# 打包为可执行脚本（推荐）
mvn package appassembler:appassembler:generate-daemons
ln -s $(pwd)/target/appassembler/bin/agent ~/.local/bin/agent

# 之后
agent                           # 新会话
agent --resume                  # 选择历史会话
agent --resume abc-123          # 恢复指定会话
agent --debug                   # 调试模式
agent --list-sessions           # 列出会话
```

### 12.2 Main 入口改造

当前 `Main.java` 是 `@SpringBootApplication` 直接 `SpringApplication.run`，**这会启动 Web 容器**。需要改成 CLI bootstrap：

```java
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        // 用 SpringApplication 但不启动 web
        // 关键：spring.main.web-application-type=none
        ConfigurableApplicationContext ctx = SpringApplication.run(Main.class, args);
        // 注册 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(ctx::close));
        // 启动 REPL
        CliRepl repl = ctx.getBean(CliRepl.class);
        repl.run();
        ctx.close();
        System.exit(0);
    }
}
```

配套 `application.yml`：
```yaml
spring:
  main:
    web-application-type: none   # 关键：禁用 Web 容器
    banner-mode: off
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen3-max

agent:
  project-root: ${user.dir}
  session-db: ~/.local-cli-copilot/sessions.db
  mcp-config: ~/.local-cli-copilot/mcp.json
  trusted-paths: ~/.local-cli-copilot/trusted-paths.json
  shell:
    default-timeout-seconds: 30
    max-output-bytes: 102400
    max-concurrent: 4
  budget:
    context-window-max: 128000
    max-total-tokens: 200000
    max-steps: 30
```

环境变量 `DASHSCOPE_API_KEY` 从 shell profile 读（推荐写入 `~/.zshrc` 或 `~/.bashrc`）。

### 12.3 .env 文件的处理

**删除 `.env` 文件，不再使用**：
- Spring Boot 默认不读 `.env`，需要额外配置 loader，反而割裂
- `application.yml` + 系统环境变量是 Spring 标准做法
- 腾讯云凭证（如果将来要用）走 `TENCENTCLOUD_SECRET_ID` / `TENCENTCLOUD_SECRET_KEY` 环境变量

---
## 6. 上下文工程

### 6.1 三层上下文

```
┌─────────────────────────────────────────────────┐
│ System Layer（固定）                             │
│  - Agent 角色描述                                │
│  - 工具列表（自动从 @Tool 生成）                 │
│  - 当前时间、模型名、项目根路径                  │
│  大小：~4K tokens                                │
├─────────────────────────────────────────────────┤
│ Project Layer（启动 + /load 时刷新）             │
│  - 文件树（深度 3，最多 1000 文件）              │
│  - CLAUDE.md / README.md / .gitignore            │
│  - 关键配置（pom.xml / package.json / go.mod）    │
│  大小：~8K tokens                                │
├─────────────────────────────────────────────────┤
│ Session Layer（运行时累积）                      │
│  - 用户消息 + Agent 回复 + Tool 调用记录         │
│  大小：剩余预算                                 │
└─────────────────────────────────────────────────┘
```

### 6.2 ProjectScanner（启动期执行一次）

```
输入：项目根路径
输出：ProjectContext（文件树 + 关键文件内容）

规则：
  1. 应用 .gitignore 规则
  2. 排除 node_modules / target / build / dist / .git / __pycache__ 等
  3. 深度限制 3（可通过 /config 调整）
  4. 文件数超过 1000 → 仅保留源码文件 + 按 mtime 排序取前 1000
  5. 自动加载 CLAUDE.md（项目级 Agent 配置）
  6. 自动加载 README.md（截断到 200 行）
  7. 检测包管理器（pom.xml → maven, package.json → npm）
```

### 6.3 CLAUDE.md（项目级配置）

放在项目根目录，类似 Claude Code 的项目记忆：

```markdown
# Project: my-app

## 约定
- 用 Lombok 减少样板代码
- 不要直接修改生成的 entity 类，改用 builder
- 所有公共 API 必须有单元测试

## 常用命令
- 测试：mvn test
- 打包：mvn clean package -DskipTests
- 运行：mvn spring-boot:run

## 关键路径
- Controller: src/main/java/.../controller/
- Service:   src/main/java/.../service/
- 配置:      src/main/resources/application.yml
```

`ProjectScanner` 自动加载并注入到 System Layer。

### 6.4 Token 预算（复用现有 AgentBudget）

| 配额项 | 默认值 | 备注 |
|---|---|---|
| `contextWindowMax` | 128000 | 按模型动态调整 |
| `systemReserved` | 4000 | system + tools schema |
| `projectReserved` | 8000 | project layer |
| `memoryTokenReservation` | 4096 | completion 预留 |
| `maxSingleCallCompletion` | 4096 | 单次最大输出 |
| **sessionReserved** | 剩余 | 消息历史 |

`ContextBuilder.buildMessages(task)` 流程：

1. 装配 system message
2. 装配工具 schema
3. 装配 project context（按文件大小截断）
4. 装配 session messages，超额时触发 `ContextCompressionHook`
5. 仍超 → 报 `CONTEXT_OVERFLOW`，agent 自动 `/compact`

### 6.5 压缩策略

**手动触发**：`/compact`
**自动触发**：`sessionUsed / sessionReserved > 0.8` 时，在 step 间隙触发

压缩算法：
```
1. 保留最近 K=5 轮原文（user + assistant + tool 完整记录）
2. 剩余历史扔给一个独立的"压缩 Agent"（system: "你是会话摘要器"）
3. 摘要模板：
   - 用户的核心目标是什么
   - 已经做了哪些事、得到了什么结论
   - 待解决的问题
   - 关键引用（文件路径、命令、错误信息）
4. 摘要上限 1500 tokens
5. 替换历史为单个 system message："以下是早期对话摘要：..."
```

---
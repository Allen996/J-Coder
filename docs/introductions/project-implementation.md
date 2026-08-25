# J-Coder 项目实现说明

> 本文面向需要阅读源码、理解模块协作和扩展功能的开发者。正文只引用源码文件名，不复制实现代码；类名用于定位职责，具体算法和分支请结合对应文件阅读。

## 1. 项目定位与总体结构

J-Coder 是一个本地运行的 Java 终端 AI 编程助手。它不是简单的“输入问题、输出答案”客户端，而是把一次编程请求拆成几个可组合的运行时能力：

- 通过 JLine 提供交互式 CLI、历史、多行输入、`@file` 引用和 Shell 透传。
- 通过 Spring AI 的 ChatModel 和工具回调驱动 ReAct 循环。
- 通过工具网关统一处理工具查找、权限、超时、重试、缓存、错误反馈和可逆副作用回滚。
- 通过静态层、动态层和上下文预算控制长对话输入。
- 通过短期、中期、长期记忆保存会话信息，并维护 `MEMORY.md` 索引。
- 通过 TaskPlan、SubTask、DAG 调度、Checkpoint 和 VERIFY/FIX 闭环把复杂需求变成可恢复任务。

源码按职责主要分为三层：

1. `org.example.cli`：用户交互层，负责把终端输入转成 Slash、Shell 或 Agent 请求，并把事件渲染回终端。
2. `org.example.agent.core`：Agent 运行时和任务系统，负责 ReAct 执行、事件、预算、取消、任务编排和验证。
3. `org.example.agent.context` 与 `org.example.agent.tool`：上下文/记忆基础设施和本地工具基础设施，分别解决“给模型什么信息”和“模型能安全做什么”。

## 2. 启动与 Spring 容器初始化

启动入口是 `src/main/java/org/example/cli/Main.java`。

启动过程如下：

1. 设置 JLine 终端相关系统属性，Windows 环境优先使用 JNA 终端实现，并关闭不必要的 Spring/JLine banner。
2. 创建 Spring Boot 应用，并明确设置为非 Web 应用，因此不会启动 Tomcat。
3. `@SpringBootApplication` 显式扫描 `org.example.cli`、`org.example.agent.core`、`org.example.agent.tool`、`org.example.agent.context` 四个包树。
4. `@ConfigurationPropertiesScan` 扫描 CLI、core、tool 下的配置属性类。
5. Spring 完成组件、配置类和 Spring AI 自动配置后，从容器中取出 `ReplLoop`。
6. 调用 `ReplLoop` 的主循环；REPL 结束后关闭 ApplicationContext，释放线程池、终端和其他资源。

模型相关配置位于 `src/main/resources/application.yml`，构建和依赖位于 `pom.xml`。项目使用 Java 17、Spring Boot 3.2、Spring AI Alibaba/DashScope、JLine 3 和 Maven。API Key 应通过环境变量提供，不应把真实密钥写入文档或提交到版本库。

Spring AI 的 `ChatModel`、工具回调提供器等基础设施主要由 Spring AI 自动配置提供；项目自己的工具类通过 `@Tool` 方法暴露给 Spring AI，最终由 `ToolCallbackProvider` 汇总。

## 3. CLI 交互链路

### 3.1 REPL 主循环

`src/main/java/org/example/cli/repl/ReplLoop.java` 是 CLI 的总调度器。它持有运行时、会话、命令注册表、输入路由器、渲染器、任务编排器和记忆钩子等依赖。

每次循环的顺序是：

1. 用 JLine 创建 `LineReader` 并读取一行。
2. 由 `MultiLineReader` 判断括号、代码块等场景是否需要继续读取。
3. `InputRouter` 将输入分为 Slash 命令、Shell 命令或普通 Agent 请求。
4. Slash 输入交给 `SlashCommandRegistry`；Shell 输入交给 `ShellPassthrough`；普通输入进入 Agent 流式执行。
5. Agent 事件不会直接从工作线程写终端，而是由 `CliRenderer` 等观察者放入渲染队列，再由 REPL 主线程定期取出并刷新。
6. `StatusLine` 和 `TaskProgressRenderer` 在执行期间显示状态和任务进度。

这种设计把“执行线程”和“终端写线程”分开，避免 Reactor 回调、工具线程和终端输出相互竞争。

### 3.2 输入辅助组件

- `src/main/java/org/example/cli/input/InputRouter.java`：识别输入类型，并处理 Shell 前缀。
- `src/main/java/org/example/cli/input/AtFileResolver.java`：解析 `@file` 引用，将文件内容和路径信息组织为模型输入；无法解析的引用会保留为普通文本并提示用户。
- `src/main/java/org/example/cli/input/ShellPassthrough.java`：执行用户明确要求透传的 Shell 命令，并把输出返回 CLI。
- `src/main/java/org/example/cli/session/SessionState.java`：保存当前 CLI sessionId、计划相关状态以及会话级交互状态。
- `src/main/java/org/example/cli/bootstrap/CliContext.java`：为 Slash 命令提供统一运行上下文，避免每个命令重复查找 CLI 依赖。

### 3.3 Slash 命令

`src/main/java/org/example/cli/command/SlashCommand.java` 定义命令扩展接口；`SlashCommandRegistry` 收集所有命令 Bean，根据命令名完成分发。命令实现集中在 `src/main/java/org/example/cli/command/impl/`，包括：

- 会话/上下文：`ContextCommand`、`CompactCommand`、`ClearCommand`、`MemoryCommand`、`LoadCommand`、`ExportCommand`。
- 模型与运行控制：`ModelCommand`、`VerboseCommand`、`AutoCommand`、`ExitCommand`、`PauseCommand`、`ResumeCommand`。
- 任务：`PlanCommand`、`PlanResumeCommand`、`TaskCommand`、`TasksCommand`、`VerifyCommand`。
- 工程辅助：`InitCommand`、`DiffCommand`、`CostCommand`、`McpCommand`、`UndoCommand`、`HelpCommand`。

这些类都作为 Spring Bean 注册，注册表通过接口集合自动发现；新增 Slash 命令通常只需实现接口并标注 `@Component`，再按接口约定提供命令名和执行逻辑。

## 4. Agent 执行主链路

Agent 对外抽象位于 `src/main/java/org/example/agent/core/runtime/AgentRuntime.java`，默认实现是 `src/main/java/org/example/agent/core/impl/AgentRuntimeImpl.java`。

一次普通请求的链路为：

1. `ReplLoop` 把最终文本、sessionId、角色和 promptId 封装为 `AgentTask`。
2. `AgentRuntimeImpl.stream` 创建 executionId，选择任务预算，创建事件记录、Token 预算观察者、步数观察者和超时观察者。
3. 运行时调用 `ContextBuilder` 生成模型消息，并创建 `ReActLoop`。
4. `SpringAiReactAgentProvider` 负责把项目运行时接到 Spring AI Alibaba 的 ReactAgent 能力；`ReactAgentProvider` 是可替换的抽象接口。
5. ReAct 循环让 ChatModel 产生思考、工具调用或最终回答。工具调用通过 `ToolGateway`，而不是由 CLI 直接执行。
6. 事件观察者记录步骤、预算、工具动作、输出和错误；`SinkEmittingObserver` 把事件推入 Reactor Flux，`CliRenderer` 负责终端显示。
7. 执行完成后，运行时持久化本轮消息，触发中期记忆更新，并通知长期记忆维护器当前活动 session。
8. `ExecutionRegistry` 保存流式执行的 `AgentHandle`，因此 `cancel` 可以按 executionId 找到执行并触发取消。
9. 运行时根据正常完成、主动终止或异常生成 `AgentExecutionResult` 和 `AgentExecutionRecord`。

`AgentRuntimeImpl` 中每次执行都重新创建预算观察者和部分执行状态，避免不同请求共享计数器。全局观察者通过运行时注册，执行级观察者则在每次执行准备阶段追加。

## 5. 上下文工程

### 5.1 两层上下文

核心装配器是 `src/main/java/org/example/agent/context/builder/ContextBuilder.java`。

- Static Layer 保存相对稳定的信息：角色定义、工具列表、代码修改推理约束、运行时元信息。
- Dynamic Layer 保存随请求变化的信息：消息历史、中期记忆、长期记忆、记忆索引、临时信息以及任务计划上下文。

`StaticLayer`、`DynamicLayer`、`ContextLayer`、`ContextEntry`、`ContextKey` 和 `LayerSeparator` 共同定义分层数据结构。上下文每个 key 都有文本、估算 token、来源和刷新时间，便于独立截断和观察。

### 5.2 构建与压缩

`ContextBuilder` 先装载静态层，再装载动态层，然后计算静态内容、动态内容和当前输入的估算 token 数。当总量达到 `ContextBudgetPolicy` 的压缩阈值时，调用 `ConversationCompressor` 压缩 session 历史，只替换 messages 层；如果压缩后仍超过窗口，则抛出上下文溢出异常。

`ContextAwareAgentBudgetFactory` 根据上下文预算计算 Agent 默认执行预算。`AutoCompressionObserver` 为运行过程中的自动压缩提供观察者入口；`PromptDumpObserver` 可把最终 prompt 记录下来，便于调试模型实际看到的内容。

项目上下文由 `ProjectScanner` 扫描，`GitignoreMatcher` 过滤不应进入上下文的文件，`ProjectContextCache` 缓存扫描结果，`ProjectContext` 和 `FileTreeNode` 表达扫描结果。这样，项目树信息可以进入动态上下文而不必每轮重复完整扫描。

## 6. 记忆系统

记忆系统位于 `org.example.agent.context.memory`，采用短期、中期、长期和索引协作的结构。

- `SessionMessageStore` 保存当前 session 的原始对话消息，是短期记忆和消息压缩的输入。
- `FlashMemorySummarizer` 负责把近期对话压缩成短期摘要。
- `MidTermStore` 保存 session 级中期信息；`MemoryTurnHook` 在每轮完成后触发增量更新，在 CLI 空闲超过阈值时支持整体重生成。
- `LongTermStore` 读写长期记忆文件；`LongTermMaintainer` 负责提取、维护和异步更新长期候选。
- `PendingLongTermCandidates` 保存待处理候选，`MemoryIndexSynchronizer` 将长期文件与索引同步。
- `MemoryIndex` 保存记忆文件索引，`MemoryRecallScorer` 根据请求相关性进行加权召回和阈值门控，`MemoryPromptRegistry` 管理记忆相关 prompt。
- `MemoryModelGateway` 调用记忆专用模型；`Frontmatter`、`MemoryFile` 负责记忆文件头和文件模型。

记忆相关模型配置由 `ModelHttpClientConfig` 和 `LightweightChatModelConfig` 提供。长期记忆通常落在项目记忆目录中，索引文件用于让模型知道有哪些可召回主题，而不是把所有历史全文塞进 prompt。

一次 Agent 回合结束时，`AgentRuntimeImpl` 先保存 turn，再通过 `MemoryTurnHook` 更新中期记忆，并把 session 标记给 `LongTermMaintainer`；因此主回答路径和长期维护路径解耦，记忆失败不会替换主流程结果。

## 7. 工具系统

### 7.1 工具注册与发现

工具实现位于 `org.example.agent.tool`：

- `FileTools`：文件读取、写入、编辑和目录相关操作。
- `GrepTools`：内容搜索。
- `ShellTools`：Shell 执行。
- `GitTools`：Git 状态、差异和版本控制相关操作。
- `TaskPlanTools`：创建计划、推进子任务、保存 checkpoint 和查询任务状态。

这些工具类作为组件扫描到 Spring 容器，并通过 `@Tool` 方法生成 Spring AI 工具回调。`ToolDescriptorRegistry` 管理工具的风险、是否只读、是否可逆、超时和是否可缓存等元数据。`ToolConfig` 通过显式 `@Bean` 注册工具回调提供器和工具线程池。

### 7.2 ToolGateway 中央网关

`ToolGateway` 是 ReActLoop 与实际工具之间的唯一中央入口。它的调用顺序是：解析参数 → 发送 pre-check 事件 → 按名称查找回调和 descriptor → 发送 action-invoked 事件 → 在 `toolExecutor` 中执行并施加超时 → 交给 `RetryPolicy` 处理可重试失败 → 成功时写入 `ToolResultStore`（若工具可缓存）→ 发送 observation 事件。

失败时由 `FailureClassifier` 分类为参数、瞬时、逻辑等失败，再转换为模型可理解的结构化错误。`ToolError`、`ToolResult`、`ToolErrorCode` 和 `ToolExecutionException` 定义错误协议。逻辑失败且工具可逆时，`SideEffectTracker` 通过 `InMemorySideEffectTracker` 回滚已记录副作用，并把 rollback 信息反馈给模型，以便重新规划。

工具安全由工具自身的闸门完成：

- `PathGate` 校验文件路径是否位于可信范围。
- `TrustedPaths` 维护项目根目录和允许访问的路径。
- `CommandGate` 拦截危险命令。
- `ExecutionGate` 处理需要授权或执行策略控制的操作。

结果缓存由 `ToolResultStore` 抽象、`FileSystemToolResultStore` 实现，`RecallToolResult` 允许模型或命令重新取回已外置的大结果。`CliToolProperties` 绑定 `cli.tool` 配置，包括并行执行、默认超时、线程池和结果缓存策略。

## 8. 任务规划与验证闭环

任务领域模型位于 `org.example.agent.core.task`：`TaskPlan` 表示目标计划，`SubTask` 表示节点，`SubTaskSpec` 表示创建规格，`PlanEdge` 表示依赖边，状态由 `TaskPlanStatus`、`SubTaskStatus` 和 `SubTaskType` 表达。

`TaskScheduler` 根据子任务依赖构造和校验 DAG；`TaskPlanRepository` 将计划、子任务和 checkpoint 持久化到本地文件；`TaskOrchestrator` 维护当前活动计划，负责创建、恢复、启动、完成、失败、跳过和串行推进。

计划推进时：

1. 选择依赖已满足的 PENDING 子任务。
2. 标记为 IN_PROGRESS 并持久化。
3. `AgentRuntimeSubTaskExecutor` 把子任务转换成 AgentTask，调用 AgentRuntime 执行。
4. 子任务完成后写入 artifacts 和 checkpoint。
5. 对 VERIFY 类型子任务调用 `VerifyRunner` 执行真实构建/测试命令。
6. 通过则发布 `VerifyPassedEvent` 并进入 VERIFIED；失败则发布 `VerifyFailedEvent`，由 `TaskOrchestrator` 局部插入 FIX 子任务，再重新验证。
7. 上游失败时按依赖关系级联跳过下游；VERIFY 不允许被普通跳过，从而保证完成闭环。

`TaskEventPublisher` 和 `TaskObserver` 体系负责发布与消费计划、子任务和验证事件。`TaskLoopObserverHub` 把任务事件与 Agent 循环观察机制连接起来；`TaskProgressRenderer` 将这些事件转成 CLI 进度显示。`TaskPlanContextAssembler` 把活动计划和当前子任务信息放入 Dynamic Layer，所以模型在执行子任务时能看到当前目标、依赖、进度和下一步。

恢复流程由 `ResumeCommand`、`PlanResumeCommand`、`TaskPlanRepository` 和 `TaskOrchestrator.adoptPlan` 协作：启动或用户命令发现本地 ACTIVE plan 后，读取计划和子任务文件，恢复内存状态，再从可执行节点继续。

## 9. 事件、观察者与输出

Agent 事件模型位于 `org.example.agent.core.event`，包括思考、动作预检、动作调用、观察结果、token/loop 预算、完成、错误和回滚等事件。`ReActLoopObserver` 是观察者接口。

主要观察者包括：

- `EventRecordingObserver`：记录一次执行的完整事件。
- `LoopStepObserver`：限制最大循环步数。
- `TokenBudgetObserver`：限制 token 预算。
- `TimeoutObserver`：限制执行时长。
- `ContextCompressionHook`：在观察阶段连接上下文压缩。
- `SinkEmittingObserver`：把事件发送到 Reactor Flux。
- `AutoCompressionObserver`：监听并触发自动压缩。

CLI 侧的 `CliRenderer` 注册为运行时观察者，把流式回答、工具动作、错误和任务事件转成终端行；`StartupBanner`、`StatusLine`、`TaskProgressRenderer` 分别负责启动提示、当前阶段状态和计划进度。`ReplLoop` 主线程定期 drain 渲染队列，因此观察者不直接操作终端。

## 10. Spring Bean 注册清单

### 10.1 启动与 CLI Bean

以下类由组件扫描注册为默认 singleton Bean：

- `ReplLoop`、`SessionState`、`InputRouter`、`AtFileResolver`、`ShellPassthrough`。
- `SlashCommandRegistry`。
- `CliRenderer`、`StartupBanner`、`StatusLine`、`TaskProgressRenderer`。
- `AutoCommand`、`ClearCommand`、`CompactCommand`、`ContextCommand`、`CostCommand`、`DiffCommand`、`ExitCommand`、`ExportCommand`、`HelpCommand`、`InitCommand`、`LoadCommand`、`McpCommand`、`MemoryCommand`、`ModelCommand`、`PauseCommand`、`PlanCommand`、`PlanResumeCommand`、`ResumeCommand`、`TaskCommand`、`TasksCommand`、`UndoCommand`、`VerboseCommand`、`VerifyCommand`。

### 10.2 Agent Core Bean

- `AgentRuntimeImpl`：`AgentRuntime` 的生产实现。
- `SpringAiReactAgentProvider`：接入 Spring AI Alibaba ReactAgent 的 provider。
- `TaskOrchestrator`、`AgentRuntimeSubTaskExecutor`、`TaskScheduler`、`TaskPlanRepository`。
- `TaskEventPublisher`、`TaskLoopObserverHub`、`TaskPlanTools`、`TaskPlanContextAssembler`、`VerifyRunner`。

`TaskSystemConfig` 是任务系统显式配置类，注册 `taskOrchestratorConfig`，从 `cli.project-root` 等配置推导验证失败上限和重试预算，注入 `TaskOrchestrator`。任务系统中的 `TaskPlan`、`SubTask`、事件类和各种状态枚举只是领域对象，不会因为位于 `core.task` 包中就自动成为 Bean。

### 10.3 Context 与 Memory Bean

- `ContextBuilder`、`ContextAwareAgentBudgetFactory`。
- `StaticLayer`、`DynamicLayer`、`ProjectScanner`、`ProjectContextCache`。
- `SessionMessageStore`、`ConversationCompressor`、`AutoCompressionObserver`、`PromptDumpObserver`。
- `FlashMemorySummarizer`、`MemoryTurnHook`、`MidTermStore`、`LongTermStore`、`LongTermMaintainer`。
- `MemoryIndex`、`MemoryIndexSynchronizer`、`MemoryRecallScorer`、`MemoryPromptRegistry`、`MemoryModelGateway`、`PendingLongTermCandidates`。

`ContextConfig` 是显式配置类，除上下文预算策略外，还负责按项目根路径创建记忆存储和索引相关对象。当前源码中 `LongTermStore`、`MemoryIndex`、`MidTermStore` 同时存在组件注解和配置类 `@Bean` 工厂方法，属于同类型的多个候选来源；实际注入时应结合 Bean 名称、类型匹配和启动日志确认最终采用的对象，扩展这些类时不要再默认假设容器中只有一个实例。`ContextBudgetPolicy` 则由 `agent.context.*` 配置驱动，供 `ContextAwareAgentBudgetFactory` 和 `ContextBuilder` 使用。

`ModelHttpClientConfig` 提供主模型 HTTP 客户端构建器和带超时的请求工厂；`LightweightChatModelConfig` 提供名为 `memoryChatModel` 的记忆专用模型，并将其设置为非自动注入候选，避免它与主 `ChatModel` 发生类型注入冲突。记忆模型不可用时，`MemoryModelGateway` 负责按当前实现策略禁用记忆写入或回落到可用模型。

### 10.4 Tool Bean

- 工具组件：`FileTools`、`GrepTools`、`ShellTools`、`GitTools`。
- 网关和元数据：`ToolGateway`、`ToolDescriptorRegistry`。
- 安全：`PathGate`、`TrustedPaths`、`CommandGate`、`ExecutionGate`。
- 失败与重试：`FailureClassifier`、`RetryPolicy`。
- 副作用：`InMemorySideEffectTracker`。
- 结果缓存：`FileSystemToolResultStore`、`RecallToolResult`。

`ToolConfig` 是显式配置类，提供 `toolCallbackProvider` 和名为 `toolExecutor` 的工具线程池；前者把 `FileTools`、`GrepTools`、`GitTools`、`ShellTools`、`RecallToolResult` 等带 `@Tool` 方法的 Bean 聚合为 Spring AI 的 `MethodToolCallbackProvider`，后者在容器销毁时执行 shutdown。`CliToolProperties` 通过 `@ConfigurationProperties(prefix = "cli.tool")` 绑定工具执行配置，启动入口中的 `@ConfigurationPropertiesScan` 负责把它注册为可注入配置 Bean。`SpringAiReactAgentProvider` 和 `ToolGateway` 都依赖工具回调提供器；前者用于组装 Agent，后者建立按工具名索引的回调表。

项目没有使用 `@EventListener`、`ApplicationListener` 或 `@Async` 来连接领域事件；core/event 和 task/event 下的事件主要是领域数据对象，事件观察和转发由显式注入的 observer/hub 完成。

### 10.5 外部自动配置 Bean

除项目源码中的 Bean 外，Spring Boot/Spring AI Alibaba 会按依赖和 `application.yml` 自动配置若干 Bean，最重要的是：

- 主 `ChatModel`：由 DashScope starter 根据 `spring.ai.dashscope` 配置创建。
- Spring/Jackson/Reactor/JLine 等基础设施 Bean：由相应 starter 或库初始化。

项目自己的 `toolCallbackProvider` 不是依赖描述中的泛化自动配置，而是由 `ToolConfig` 显式聚合项目工具 Bean 产生。`AgentRuntimeImpl` 和 `ToolGateway` 都依赖主 `ChatModel`、工具回调提供器等对象，因此必须保证 DashScope API Key、模型名和 Spring AI 版本配置有效。

## 11. 关键联动关系总览

普通聊天：

`Main` → `ReplLoop` → `InputRouter` → `AtFileResolver` → `AgentTask` → `AgentRuntimeImpl.stream` → `ContextBuilder`/`ReActLoop` → `ToolGateway` → 工具 Bean → `CliRenderer` → 终端。

上下文与记忆：

`SessionMessageStore` 保存原始消息 → `ConversationCompressor` 在预算不足时压缩 → `ContextBuilder` 组织 Static/Dynamic Layer → `MemoryTurnHook` 更新中期记忆 → `LongTermMaintainer` 维护长期文件 → `MemoryIndexSynchronizer` 更新索引 → `MemoryRecallScorer` 在下一轮选择相关记忆。

任务执行：

Slash 命令或 `TaskPlanTools` 创建 `TaskPlan` → `TaskScheduler` 校验 DAG → `TaskOrchestrator` 选择节点 → `AgentRuntimeSubTaskExecutor` 调用 AgentRuntime → `TaskPlanContextAssembler` 注入任务上下文 → `VerifyRunner` 真实验证 → 通过则结束，失败则插入 FIX → `TaskEventPublisher`/观察者/渲染器同步状态。

工具安全：

模型产生 tool call → `ToolGateway` 查 descriptor → 工具内部通过 `PathGate`/`CommandGate`/`ExecutionGate` 检查 → `RetryPolicy` 和 `FailureClassifier` 处理异常 → `SideEffectTracker` 在逻辑失败时回滚 → 结构化错误返回模型进行下一轮规划。

## 12. 阅读源码的推荐顺序

1. 先看 `README.md` 和 `pom.xml`，了解目标、依赖和运行方式。
2. 看 `Main.java`、`ReplLoop.java`、`InputRouter.java`，建立 CLI 入口和输入分流模型。
3. 看 `AgentRuntime.java`、`AgentRuntimeImpl.java`、`ReActLoop.java`，理解一次 Agent 执行的生命周期。
4. 看 `ContextBuilder.java`、`ContextConfig.java`、`ContextBudgetPolicy.java`，理解 prompt 如何装配和压缩。
5. 看 `ToolGateway.java`、`ToolConfig.java`、各工具类及 sandbox 包，理解工具调用安全边界。
6. 看 `TaskOrchestrator.java`、`TaskScheduler.java`、`TaskPlanRepository.java`、`VerifyRunner.java`，理解计划、DAG、持久化和验证闭环。
7. 最后看 memory 包和 `src/main/resources/prompts/memory/`，理解记忆模型的输入输出与文件同步。

## 13. 端到端联动案例

为帮助读者把前述章节的模块关系落到一次真实交互上，本节跟踪一条贯穿 CLI、Agent、Context、Memory、Tool、Task 全链路的请求：用户在项目根目录中向 REPL 输入一段话，希望助手把 `src/main/java/org/example/agent/tool/gateway/ToolGateway.java` 里的默认工具超时调整为 30 秒，并在改完之后自动跑一次构建确认没有问题。

> 我在 src/main/java/org/example/agent/tool/gateway/ToolGateway.java 看到默认超时有点短，能不能改成 30 秒？改完帮我跑一下编译，确保没问题。

下面按时间顺序拆解这条请求在系统内部的流转，把前面章节出现过的模块一次性串起来。

### 13.1 CLI 入口与输入分流

`Main` 启动 Spring 容器后取出 `ReplLoop`。`ReplLoop` 调起 JLine 的 `LineReader` 读取一行，`MultiLineReader` 先判断括号、代码块等场景是否需要继续读行；确认是单行输入后交给 `InputRouter`。

`InputRouter` 看到输入既不是 `/` 前缀的 Slash 命令，也不是 `!` 前缀的 Shell 透传，因此按普通 Agent 请求处理。`AtFileResolver` 扫描文本，没有匹配到 `@` 形式的本地文件引用，于是把原始字符串、当前 `SessionState` 中的 `sessionId`、角色和 promptId 一起打包成 `AgentTask`，提交给 `AgentRuntimeImpl.stream`。

### 13.2 运行时初始化与上下文构建

`AgentRuntimeImpl` 为本次请求生成 executionId，按 `agent.context.*` 配置创建一份独立的 `ContextBudgetPolicy`，并按预算策略绑定 Token 预算、步数和超时三个观察者。同时 `MemoryTurnHook` 的会话钩子被通知进入新回合。

随后 `ContextBuilder` 装配模型输入：

1. `StaticLayer` 写入角色定义、可用工具清单（含 `FileTools`、`GrepTools`、`ShellTools`、`TaskPlanTools`）、修改代码时的推理约束和运行时元信息。
2. `DynamicLayer` 写入本 session 的 `SessionMessageStore` 历史、`MidTermStore` 当前会话状态、`MemoryIndex` 暴露的可召回主题，以及当前活跃 `TaskPlan` 的占位（此刻还没有活动计划）。


当静态、动态两层加上当前请求的估算 token 接近 `ContextBudgetPolicy` 阈值时，`ConversationCompressor` 会先尝试压缩 session 历史；本案例历史很短，不会触发压缩。

### 13.3 模型规划与任务创建

模型在第一轮思考中判断这次请求包含两个独立目标（修改源码、跑编译验证），并且中途可能出现构建失败需要回头改，于是调用 `TaskPlanTools` 的 create_plan 工具。`ToolGateway` 接收调用后查 `ToolDescriptorRegistry` 找到对应回调和 descriptor，按工具名把控制权交给任务系统。

`TaskScheduler` 校验子任务依赖 DAG，确认「读取并修改文件 → 运行构建验证」是合理顺序，`TaskPlanRepository` 把计划、子任务和初始 checkpoint 写入本地文件。`TaskPlanContextAssembler` 同步把活动计划和当前子任务注入 Dynamic Layer，使后续轮次模型都能看到目标、依赖与进度。

### 13.4 读取并修改源文件

进入第一个子任务。`AgentRuntimeSubTaskExecutor` 把子任务包装为新的 `AgentTask`，调用 `AgentRuntimeImpl.stream` 启动一次嵌套的 ReAct 流程。模型先后调用：

- `GrepTools.search_content`：在 `src/main/java/org/example/agent/tool/gateway/` 范围内搜索超时字段，确认默认值的精确位置。
- `FileTools.read_file`：读取目标片段，判断相邻上下文。
- `FileTools.edit_file`：把默认超时改为 30 秒。

每次工具调用都走 `ToolGateway`：

1. 解析参数，发送 pre-check 事件。
2. `ToolDescriptorRegistry` 给出 risk、可逆性、超时与缓存策略。
3. 文件类工具内部经过 `PathGate` 校验路径落在 `TrustedPaths` 中。
4. `toolExecutor` 施加超时执行；写操作同时被 `InMemorySideEffectTracker` 记录原内容。
5. 成功后写入 `ToolResultStore`（只对可缓存工具生效），并发出 observation 事件。

模型收到修改结果后产出子任务 1 的总结，`TaskOrchestrator` 持久化 checkpoint 并把子任务标记为 COMPLETED。

### 13.5 真实构建与 VERIFY 闭环

第二个子任务被标记为 VERIFY 类型。`VerifyRunner` 解析 `SubTaskSpec` 中的命令（Maven 编译），调用 `ShellTools.execute_command` 真实执行 `mvn -q -DskipTests compile`。`CommandGate` 先做静态风险过滤，`ExecutionGate` 决定是否需要用户授权；普通构建命令继续放行。

如果构建成功：

1. `VerifyRunner` 发布 `VerifyPassedEvent`。
2. `TaskOrchestrator` 将子任务置为 VERIFIED，关闭计划，并向 `MemoryTurnHook` 发出计划完成信号。

如果构建失败：

1. `VerifyRunner` 发布 `VerifyFailedEvent` 并附带失败日志摘要。
2. `TaskOrchestrator` 在失败节点之后局部插入一个 FIX 子任务，重新跑一次 AgentRuntime，由模型根据错误日志调整修改，再次进入 VERIFY。
3. FIX 循环的次数受 `cli.project-root` 推导出的 `verifyFailureLimit` 控制；超出后整条计划置为 FAILED，等待用户通过 `/plan` 或 `/tasks` 介入。

### 13.6 记忆、事件与渲染

主回答路径与上述验证流程并行推进。每次 `AgentRuntimeImpl` 完成一个 turn：

- 原始消息被 `SessionMessageStore` 保存为短期记忆候选。
- `MemoryTurnHook` 在回合结束时调用 `MidTermStore` 写入本轮要点，并把 sessionId 通知给 `LongTermMaintainer`。
- `LongTermMaintainer` 异步提取候选，写入项目记忆目录；`MemoryIndexSynchronizer` 同步更新 `MEMORY.md`。这些维护动作不会阻塞本轮主回答，即使失败也不影响最终回复。

事件层面，`SinkEmittingObserver` 把工具动作、子任务进度、验证结果和模型最终回答推到 Reactor Flux。`CliRenderer` 注册为运行时观察者，把流式回答、`StatusLine` 当前阶段、`TaskProgressRenderer` 的计划进度排入渲染队列，由 `ReplLoop` 主线程统一 drain 到终端，因此 ReAct 回调、工具线程与终端写线程互不竞争。

### 13.7 闭环与下一轮复用

整条链路结束后，`TaskPlanRepository` 写入最终 checkpoint，`MemoryTurnHook` 完成增量更新，`ExecutionRegistry` 释放本次 executionId。用户下一次提问时，`ContextBuilder` 装配 Dynamic Layer 时会：

- 从 `SessionMessageStore` 拿到原始消息。
- 从 `MidTermStore` 拿到本会话压缩摘要。
- 从 `MemoryIndex` 拿到刚刚被长期化的"修改 ToolGateway 默认超时"条目。
- 从 `TaskPlanContextAssembler` 拿到已完成计划作为历史参考。

`MemoryRecallScorer` 会按相关性决定哪些记忆值得召回到本轮提示，保证后续提问无需重述背景。由于 CLI 输入分流、上下文预算、记忆异步维护、工具安全闸门、任务验证闭环和渲染解耦都已经按角色解耦，即便换成更复杂的多步骤需求，也能复用同一套协调路径，而无需重新设计模块边界。

## 14. 意图识别（两层门控 + 模型路由）

`design/intent.md` 提出的两阶段意图识别已落地在 `org.example.agent.intent` 包。

### 14.1 触发位置

- L1（粗分类）发生在 `ReActLoop.subscribe()` 入口、`buildInitialMessages()` 之前；每个 ReActLoop 实例只在首轮触发一次（`IntentContext.sticky` 标志），后续步骤不会重复分类。设计稿 §9 的"第 N 轮再跑 L1"留作后续扩展。
- L2（工具语义门控）发生在 `ReActLoop.dispatchToolCalls()` 拿到模型产出的 `assistant.getToolCalls()` 后、`toolGateway.invoke()` 之前，对**每个 ToolCall** 单独判别。

`AgentRuntimeImpl.prepare(task)` 在构造 `ReActLoop` 时把 `IntentGate` 与 `LlmToolGate` 一并注入；旧的 13 参构造器通过新增 16 参重载保持兼容，旧路径与旧测试不受影响。

### 14.2 数据契约

| 类型 | 角色 |
| --- | --- |
| `IntentLabel` | 6 个枚举：READ_CODE / WRITE_PROJECT / RUN_COMMAND / CHAT_QA / PLANNING / OFF_TOPIC |
| `L1IntentResult` | L1 完整输出（primary / confidence / candidates / slots / negativeSignals / modelRouteHint / fallback） |
| `IntentContext` | 挂在 ReActLoop 上的可读上下文，提供 `primaryLabel()`、`resolvedModel()`、`recordTool()` 给 L2 / 路由 / 日志观测者 |
| `L2ToolGateResult` | L2 输出（decision / confidence / reason / suggestedAlternative） |
| `ToolGateDecision` | ALLOW / WARN / BLOCK / REWRITE |
| `ModelRouteHint` | LIGHT / CODE / GENERAL |
| `IntentAwareToolSet` | 标签 → 推荐工具集合的硬编码映射（设计稿 §6），同时给 L2 判断"工具是否在推荐集合内" |

### 14.3 L1 流水线

`IntentGate.classify()` 把 4 个组件串成一条流水线：

1. `ChatModelLlmIntentClassifier` 调一次廉价的 LLM（默认 `qwen3.7-flash`，通过 `DashScopeChatOptions.withModel` 切换），prompt 控制在 500 token 以内，强制 JSON 返回；解析失败 / 超时统一翻译成 `Outcome.fallback(reason)`。
2. `KeywordSignalExtractor` 扫中文关键词（看 / 解释 / 改 / 新增 / 跑 / 提交 / 什么是 …），同时识别反向前缀（别 / 不要 / 先别）；英文只覆盖部分关键词，留空余由 LLM 自评覆盖。
3. `SlotCompletenessChecker` 按类别必填槽位（WRITE_PROJECT 需 target_file + change_type 等）评估完整度，并对原始输入做文件名启发式补足。
4. `LocalIntentScorer` 用设计稿 §5.3 的公式融合三类信号：

   ```
   final_conf = 0.6 * llm_conf
              + 0.2 * keyword_match_score
              + 0.2 * slot_completeness_score
              - 0.15 if (rule_signal 强冲突 LLM 选择) else 0
              - 0.10 if negative_signals 非空 else 0
   ```

5. `IntentPrompter` 根据最终置信度分三档：
   - `DIRECT`（≥ 0.85）→ 不打扰用户
   - `OFFER`（0.60..0.85）→ 打印 1..N 候选 + 0 跳过，让用户选
   - `CLARIFY`（< 0.60）→ 一句话反问，回应后复用同一 LLM 上下文再跑一轮 L1

`IntentFallbackPolicy` 在以下场景兜底：L1 JSON 解析失败、L1 超时、配置开关关闭、空输入；统一产出 `OFF_TOPIC / conf=0.5 / fallback=true` 的 `L1IntentResult`，确保 loop 不会卡住。

### 14.4 L2 工具门控

`LlmToolGate.evaluate(intentContext, toolName, argsJson, step)` 在 5 条规则下做决策：

| 情形 | 决策 |
| --- | --- |
| 工具不在 `ToolDescriptorRegistry` | BLOCK |
| 工具属于 L1 推荐集合 | ALLOW |
| READ_CODE 下用写工具 | REWRITE → 按优先级替换为 read_file / grep / list_dir / glob_files |
| WRITE_PROJECT 早期 step + 读工具 | ALLOW（先读再写是合理路径） |
| WRITE_PROJECT 后期 step + 纯读（非推荐） | WARN（疑似漂移） |
| OFF_TOPIC / CHAT_QA 下任何工具 | BLOCK |
| 非推荐集合但非强冲突 | WARN |

BLOCK 不下发工具，而是以 `ToolResponseMessage.ToolResponse` 的形式把 `[error: INTENT_GATE_BLOCKED]` 摘要塞回 messages；REWRITE 则在 ReActLoop 入口把 ToolCall 改写成 `suggestedAlternative`，再走 ALLOW 路径；WARN 不改写但记录历史。失败 / 关闭 → 默认 ALLOW（设计稿 §4.1："宁可漏判不要误拦"）。

L2 的 `INTENT_GATE_BLOCKED` 错误码已加入 `ToolErrorCode`，归类为 PARAM（沿用现有沙箱层语义）。

### 14.5 模型路由

L1 跑完后，`IntentGate` 把 `modelRouteHint` 映射到 `application.yml` 中的实际模型名（默认 `qwen3.7-flash` / `qwen3.7-plus`），结果放在 `IntentContext.resolvedModel()`。`ReActLoop.buildPrompt()` 在每次构造 `DashScopeChatOptions` 时调用 `withModel(intentContext.resolvedModel())`，同一 ChatModel bean 不需要换实例。

路由结果**只在 L1 入口写入一次**，loop 内部不切换，避免每轮重建上下文。`SpringAiReactAgentProvider` 是 SPI 旁路（当前生产路径不经过它），未来若启用可按相同方式读取 `IntentContext.resolvedModel()`。

### 14.6 可观测与配置

- 每次 L1 / L2 调用写入 `Sl4jIntentLogSink`（Logger 名 `intent`），字段含 executionId / timestamp / 原始输入 / LLM 自评 / 规则建议 / 最终决策 / 降级原因。`IntentGate` 内置 64 条环形缓冲供 `/intent-stats` 直接消费。
- 配置集中在 `application.yml` 的 `cli.intent` 节点下：
  ```yaml
  cli:
    intent:
      enabled: true
      l1: { enabled: true, timeout-ms: 3000, thresholds: { direct: 0.85, offer: 0.60 } }
      l2: { enabled: true, timeout-ms: 2000, thresholds: { allow: 0.80, warn: 0.55 } }
      model-routing: { light: qwen3.7-flash, code: qwen3.7-plus, general: qwen3.7-plus }
      fallback:     { enabled: true, default-label: OFF_TOPIC }
  ```
- 全局开关 `cli.intent.enabled=false` → 整套跳过，便于回归对比与 power user。

### 14.7 Slash 命令

`IntentStatsCommand`（`/intent-stats`）展示最近 64 条 L1 的标签分布、tier 分布、平均置信度、降级次数和最近 5 条明细。它和现有 23 个 slash 命令一样由 `SlashCommandRegistry` 自动发现，无需额外配置。

### 14.8 测试

`src/test/java/org/example/agent/intent/` 覆盖：
- 关键词提取 8 例（含英文 fallback）
- 槽位完整度 6 例（含文件名启发式）
- 本地打分器 5 例（高置信、规则冲突、负向信号、降级、候选合并）
- 降级策略 5 例（默认 / 全关 / L1 关 / 自定义兜底标签）
- 配置类 4 例（默认值、阈值夹紧、fallback 标签默认）
- 意图编排 5 例（direct / light / fallback / 空输入 / 结果缓存）
- 工具门控 9 例（ALLOW / REWRITE / BLOCK / 未知 / 早期读 / 后期读 / null 上下文 / 降级 / 非推荐集合 WARN）
- 提示器 6 例（suppress / 选号 / 选 0 / 空回车 / 澄清文本 / 空澄清）
- 工具集 3 例（read 推荐 / write 推荐 / chat/off 空集合）

整套 190 个测试（含原有 16 个）全部通过。

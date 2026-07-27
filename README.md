# MiniCode

本地运行的终端 AI 编程助手。它通过 ReAct 循环调用文件、Shell、搜索和 Git 工具，并结合上下文、记忆与任务规划，支持可追踪、可恢复的代码改造。

## 技术栈

- Java 17
- Spring Boot 3.2
- Spring AI Alibaba / DashScope
- JLine 3
- Jackson
- Maven
- JUnit 5、AssertJ、Mockito

## 已实现功能

- JLine CLI：多行输入、历史记录、`@file` 引用、Shell 透传和 Slash 命令。
- 本地工具：文件读写编辑、目录遍历、Glob、Grep、Shell、Git。
- 安全控制：路径白名单、危险命令拦截、超时、输出截断和用户授权。
- 失败处理：瞬时错误重试、参数错误反馈、逻辑错误回滚。
- 上下文工程：静态层、动态层、Token 预算和按 key 管理的上下文内容。
- 三层记忆：短期、中期、长期记忆，以及 `MEMORY.md` 索引。
- 任务系统：TaskPlan、SubTask、Checkpoint、DAG 调度和本地持久化。
- 验证闭环：自动 VERIFY，失败时插入 FIX，记录 `verify.log`。
- 任务恢复：启动扫描 ACTIVE plan，支持 `/resume <planId>` 恢复。
- 任务控制：`/tasks`、`/task`、`/verify`、`/pause`、`/plan-resume`。
- ANSI 进度渲染、工具调用日志和流式回答输出。

## 解决的问题

- 将复杂需求拆成可追踪、可依赖、可恢复的子任务。
- 避免长对话中关键信息丢失。
- 防止代码修改完成但未经过真实构建和测试。
- 降低 Shell、文件操作和自动化工具带来的安全风险。
- 将工具异常转换为模型可理解的结构化反馈。

## 快速开始

环境要求：JDK 17+、Maven 3.8+、DashScope API Key。

1. 在配置文件或环境变量中设置 API Key。
2. 执行 `mvn spring-boot:run` 启动 CLI。
3. 输入自然语言开始对话；使用 `/help` 查看全部命令。
4. 使用 `/tasks` 查看任务进度，使用 `/resume <planId>` 恢复未完成任务。

## 设计文档

- `guide.md`：总体设计
- `part1.md`：CLI
- `part2.md`：工具系统
- `part3.md`：上下文工程
- `part4.md`：记忆系统
- `part5.md`：任务系统

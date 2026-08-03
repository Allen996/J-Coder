## 7. 记忆系统

### 7.1 重新定位

记忆不是"重启恢复对话",而是让 agent 在不同时间尺度上保留与复用上下文。系统分三层,采用不同的存储格式以匹配各层的使用场景:

- **短期与中期记忆** 以 JSON 文件存储。结构化数据直接序列化,便于程序解析与反序列化,也能与 Spring AI 的 Message 模型做零成本适配——短期记忆反序列化即可直接复用为 ChatMessage 序列。
- **长期记忆** 沿用 Markdown 文件,**按主题拆分为多个文件**,文件名格式 `NNN-<主题摘要>.md`(`NNN` 为三位数字序号),每个文件头部 YAML frontmatter 记录元数据,正文按类别用 Markdown 列表组织,经用户确认的候选条目以 YAML 代码块形式追加——既便于用户直接检视、编辑、纳入版本管理,字段结构化也便于程序解析。主题拆分使每个长期记忆文件语义聚焦,便于跨主题查询与按主题管理。
- 不引入 SQL 或任何外部依赖。

**本版三条硬性约束**(相对上一版的核心改动):

1. **存储形式改造**:短期/中期落 JSON,长期按主题拆分为多文件;所有记忆文件的元数据统一上移到 frontmatter / JSON 头部字段,并**新增可评分元数据**(`summary` / `topics` / `keywords` / `importance` / `pinned`),使每个记忆单元可被独立评分与召回(§7.4)。
2. **摘要必须由配置的模型产出**:彻底移除启发式兜底路径,模型不可用即不产出记忆,绝不落盘低质量替代品(§7.5);并**按记忆文件类型配置不同的提示词**,提示词外置为资源文件(§7.6)。
3. **召回必须评分**:ContextBuilder 装配时引入加权评分 + 阈值门控,**允许召回结果为空**;取消"匹配失败就返回最近 N 条"的盲目召回(§7.7)。

**本版暂不实现**:记忆合并与去重(§7.12)、记忆淘汰 GC(§7.13)。两节保留完整设计供后续版本落地,但当前实现不包含,相关触发器、后台调度、归档文件均不产生。

### 7.2 三层架构

**短期记忆**

- 粒度:单个会话的完整对话流。
- 内容:全部 user / assistant / tool-call 原始条目,按时间顺序追加。
- 形态:每个 session 一份独立 JSON 文件 `.agent/sessions/{sessionId}/short-term.json`。文件内容是与 Spring AI Message 返回格式严格对应的消息序列,核心要求是**工具调用结果可精确提取**:
  - 每条消息至少包含 role 与 content。
  - 工具调用相关消息按 Spring AI 约定结构化:`assistant` 消息带 `tool_calls` 字段(每项含 `id`、`name`、`args`);紧随其后的 `tool` 消息带 `tool_call_id` 与 `content`(即工具返回结果)。通过 `tool_call_id` 可在 JSON 中精确索引到任一工具调用的入参与结果,无需遍历全文。
  - 加载时反序列化为 Spring AI 的 `ChatMessage` 列表,可直接送回模型,无需再做格式转换。
  - **大结果外置**:当单个工具返回结果超过体积阈值(默认 2KB,可配置)时,自动从短期记忆 JSON 中剥离,落盘为独立文件 `.agent/sessions/{sessionId}/tool-results/{tool_call_id}.json`,短期记忆 JSON 中只保留引用字段(如 `tool_result_ref`),使主对话流保持轻量。加载时若遇引用则动态加载外置文件并组装为完整 `ChatMessage`。
- 加载语义:ContextBuilder 装配时默认取最近 5 轮对话直接加入上下文;超预算则递减轮数(4→3→2→1);1 轮仍超则对该轮做 LLM 摘要压缩(提示词 profile `short_term_compress`)。不再使用滑窗淘汰。
- **不参与评分召回**:短期记忆是"最近窗口"而非"检索源",按轮数直接注入,不进入 §7.7 的评分流程。
- 写入时机:对话过程中增量追加,对话结束时落盘完整文件。
- 生命周期:session 显式结束时冻结。

**中期记忆**

- 粒度:单个 Session 的整体总结。
- 来源:每轮对话结束后由配置的记忆模型对本轮对话提取摘要;会话结束时整体重生成。
- 形态:每个 session 一份独立 JSON 文件 `.agent/sessions/{sessionId}/mid-term.json`。文件内容为固定结构的 JSON 对象,聚焦于"跨 session 复用价值"最高的四类信息:
  - **跨会话任务进度**:与历史 session 的衔接——已完成的跨会话任务、当前进行中的、阻塞中的(供下次 session 启动时接手)。
  - **会话总结**:本 session 整体目标的达成情况与关键结果。
  - **用户短期焦点**:用户在当前阶段最关心的事、最在意的问题、最常打交道的对象。
  - **当前特定背景下的规则**:本 session 内生效的临时规则、约束或上下文(下次 session 不一定继续适用)。
- 另有**头部可评分元数据**(`summary` / `topics` / `keywords` / `importance`),由模型产出,是该 session 参与跨会话召回的唯一依据(§7.4)。
- 生成方式:配置的记忆模型在每轮对话结束后调用(profile `mid_term_patch`),提示词约束结构化 JSON 输出;会话结束时整体重生成(profile `mid_term_regen`)。
- 更新时机:每轮对话结束后增量更新;会话结束时整体重生成。
- **消费**:当前 session 的 mid-term **始终注入**(它就是本次会话的上下文);**其他 session 的 mid-term** 作为可召回记忆,走 §7.7 评分流程,不相关则不注入。

**长期记忆**

- 粒度:单个项目;一个项目包含**多个**长期记忆文件,按主题拆分。
- 内容:跨 session 持续生效的项目骨架信息,聚焦于「agent 如何长期理解用户、协作、项目与外部信息」,而非代码层面的具体约定。
- 形态:项目根目录下**多个 Markdown 文件**,文件名格式 `NNN-<主题摘要>.md`,其中 `NNN` 为三位数字序号(从 001 开始递增,新建主题时分配下一个可用序号),`<主题摘要>` 由 LLM 在提取时根据该文件内条目语义自动生成(小写、短横线连接、可含中文,长度上限可配置)。每个文件头部 YAML frontmatter 记录元数据,正文按四个类别用 Markdown 列表组织,经用户确认的候选条目以 YAML 代码块形式追加:
  - **user(用户偏好)**:用户的角色、背景、知识水平、对语言/框架/工具的偏好、对输出形式的偏好等。
  - **feedback(怎么做事)**:协作风格、明确表达过的禁忌("不要 X")、对常见操作的偏好(测试/提交/文档/命名等)。
  - **project(长期目标)**:项目的目的、核心架构决策、技术栈选择、依赖红线、与同类项目的差异。
  - **reference(具体东西在哪里寻找)**:外部信息索引——第三方文档位置、关键配置文件位置、问题排查入口、与该项目相关的外部资料链接。
- **主题判定**:候选条目入库时,LLM 先在已有长期记忆文件中做主题匹配;命中已有主题则追加到对应文件,未命中则创建新文件并分配下一个可用序号(主题摘要由 profile `long_term_topic_naming` 生成)。用户也可在对话中显式指定主题(例如「这条归入 003-spring-config」)。主题判定失败的候选条目暂存到 `000-待归类.md`,等待用户后续手动整理。
- 写入时机:每轮对话结束后由配置的记忆模型对本轮对话提取候选条目,候选条目以结构化列表形式呈现给用户,经用户确认后按主题归入对应长期记忆文件;新主题则创建新文件并分配序号。
- **消费(本版改动)**:不再"session 开始时遍历所有文件全量加载"。主题拆分后文件数会随项目推进增长,全量注入不可持续。改为:
  - `pinned: true` 的条目 + `importance = 5` 的条目 → **常驻注入,不参与评分**(红线不能因为"这轮没提到"就被漏掉)。
  - 其余条目 → 以**主题文件**为评分单元,走 §7.7 评分召回。
- 更新策略:追加式更新;旧条目可标注 deprecated 但不删除;支持用户用编辑器直接维护(可直接重命名文件以调整主题摘要,或在文件间手动迁移条目)。
- 提示词设计:见 §7.6。

**记忆索引(MEMORY.md)**

- 形态:项目根目录下唯一一份 Markdown 文件 `MEMORY.md`(头部 YAML frontmatter 记录元数据),作为 `memory_index` key 承载于动态层。
- **定位调整**:`MEMORY.md` 只是**人类可读的目录页**。评分所需的结构化信号**不再从索引行解析**,而是来自各记忆文件自身的 frontmatter / JSON 头部(启动时扫描进内存评分缓存)。这样索引行可以随意排版而不影响召回质量,索引与实际文件不一致时也可随时重建。
- 内容:每行格式 `- [{类型}] <记忆文件路径> — <简介>`,长期记忆行形如 `- [topic] 001-spring-config.md — Spring 配置约定`;中期记忆行形如 `- [session] .agent/sessions/2026-07-23-001/mid-term.json — 2026-07-23 会话 001 摘要`。
- 维护:每次产生记忆文件(中期、长期)时同步更新索引。索引由内存缓存整体重渲染写出,而非增量追加,避免漂移。
- **取消 LRU 淘汰**(§7.13 暂不实现):索引收录全部记忆文件,不再限制 20 条。索引本身不整体注入上下文,注入的是 §7.7 评分后的召回结果——池子变大不会导致注入变多。
- 加载:session 开始时加载;不存在时无需加载(视为空索引)。

### 7.3 三层之间的桥梁

短期 → 中期

- 每轮对话结束后,由配置的记忆模型对本轮对话提取摘要,合并进 `mid-term.json`。
- 会话结束时整体重生成。

中期 → 长期

- 不主动**逐条**晋升。用户在对话中通过"记住.../不要..."等触发语句表达,或记忆模型在每轮对话结束后提取的候选条目,经用户确认后才**按主题写入对应长期记忆文件**(新主题则创建新文件并分配下一个可用序号)。
- **本版对重复条目的唯一手段是提取期规避**:调用 `long_term_extract` 时把已有条目的标题列表(`existing_titles`)作为输入传给模型,要求其不输出与之重复的候选;再由用户确认环节人工把关。真正的合并、语义去重推迟到后续版本(§7.12)。

冲突解决

- 同一事实跨层出现:长期 > 中期 > 短期。
- 同层多版本:保留全部并附时间戳,运行时取最新;标 `deprecated: true` 的条目排除出召回池但保留文件。

### 7.4 记忆文件格式规范

本版所有记忆文件在原有内容之外,统一携带一组**可评分元数据**,这是 §7.7 召回评分的唯一数据来源:

| 字段 | 含义 | 产出方 |
| --- | --- | --- |
| `summary` | 一句话摘要(≤ 40 字),用于索引行与召回预览 | profile `index_summary` |
| `topics` | 3-5 个中文主题词 | profile `index_summary` |
| `keywords` | 3-8 个小写英文标识符(驼峰/下划线已拆分) | profile `index_summary` |
| `importance` | 1-5 整数 | 提取时由模型给出,用户确认时可改 |
| `pinned` | 是否常驻注入,跳过评分 | 默认规则 + 用户改写 |

`pinned` 默认规则:`importance == 5` 或类别为 `feedback` 中的明确禁忌时默认 `true`,其余默认 `false`;用户可在确认候选时改写,也可事后直接编辑文件。

**短期记忆 `short-term.json`**

```json
{
  "schema": 2,
  "sessionId": "2026-08-02-001",
  "createdAt": "2026-08-02T15:30:00",
  "updatedAt": "2026-08-02T16:45:00",
  "messages": [
    { "role": "user", "content": "帮我看下 ContextBuilder 的召回逻辑", "timestamp": "2026-08-02T15:30:02" },
    { "role": "assistant", "content": "我先读一下文件。",
      "tool_calls": [
        { "id": "call_1", "name": "read_file", "args": { "path": "src/.../ContextBuilder.java" } }
      ],
      "timestamp": "2026-08-02T15:30:05" },
    { "role": "tool", "tool_call_id": "call_1", "content": "public void loadDynamicLayer(...) {...}",
      "timestamp": "2026-08-02T15:30:06" },
    { "role": "tool", "tool_call_id": "call_2",
      "tool_result_ref": ".agent/sessions/2026-08-02-001/tool-results/call_2.json",
      "size": 10240, "timestamp": "2026-08-02T15:31:10" }
  ]
}
```

短期记忆不携带可评分元数据——它不参与召回。

**中期记忆 `mid-term.json`**

```json
{
  "schema": 2,
  "sessionId": "2026-08-02-001",
  "summary": "重构记忆系统存储形式,引入召回评分与分型提示词",
  "topics": ["记忆系统", "上下文装配", "提示词"],
  "keywords": ["memory", "contextbuilder", "recall", "prompt"],
  "importance": 4,
  "createdAt": "2026-08-02T15:30:00",
  "updatedAt": "2026-08-02T16:45:00",
  "crossSessionProgress": {
    "done": ["短期记忆改为 JSON 存储"],
    "inProgress": ["ContextBuilder 召回评分"],
    "blocked": []
  },
  "sessionSummary": "确定了记忆三层的新存储形态,并明确摘要必须走配置模型。",
  "userFocus": ["召回准确率", "不要盲目注入无关记忆"],
  "contextualRules": ["本轮讨论只改设计文档,不动实现代码"]
}
```

**长期记忆 `NNN-<主题摘要>.md`**

````markdown
---
schema: 2
seq: "001"
topic: spring-配置约定
summary: Spring AI 与 DashScope 的模型配置约定,含记忆专用模型的隔离方式
topics: [Spring 配置, 模型接入, 依赖注入]
keywords: [spring, dashscope, chatmodel, bean, config]
importance: 4
pinned: false
entryCount: 2
createdAt: 2026-08-02T16:45:00
updatedAt: 2026-08-02T16:45:00
---

# user

```yaml
- id: 001-user-01
  content: 用户熟悉 Spring 生态,解释配置问题时可直接使用 Bean/注入等术语
  importance: 3
  pinned: false
  evidence: 'user: "这个 bean 为什么没注进去"'
  addedAt: 2026-08-02T16:45:00
```

# feedback

```yaml
- id: 001-feedback-01
  content: 记忆专用模型必须与主模型走独立 bean,不要共用同一个 ChatModel
  importance: 5
  pinned: true
  evidence: 'user: "记忆的模型要单独配,别跟主模型混"'
  addedAt: 2026-08-02T16:45:00
```

# project

（暂无条目）

# reference

（暂无条目）
````

**记忆索引 `MEMORY.md`**

```markdown
---
schema: 2
updatedAt: 2026-08-02T16:45:00
entries: 3
---

# memory_index

本文件是记忆目录页,供人查阅。程序按各记忆文件的 frontmatter / JSON 头部评分召回,不解析本文件的元数据。

- [topic] 001-spring-config.md — Spring AI 与 DashScope 的模型配置约定
- [topic] 002-context-assembly.md — 上下文装配的静态层/动态层结构
- [session] .agent/sessions/2026-08-02-001/mid-term.json — 重构记忆系统存储形式并引入召回评分
```

### 7.5 摘要产出:必须经由配置模型

**硬性约束:任何写入记忆文件的摘要性内容,必须是配置模型的产出。移除全部启发式兜底路径。**

现有实现(`FlashMemorySummarizer`)在模型不可用或输出解析失败时会退回启发式——截断首条 user 消息当作摘要、正则命中"记住"二字就伪造一条候选。这类产物噪声大、可信度低,一旦落盘就会污染后续召回,而用户无法分辨它并非模型产出。本版**删除** `heuristicMidTermPatch` / `heuristicFullMidTerm` / `heuristicCandidates` 三条路径。

**模型配置**

- 记忆专用模型由 `agent.memory.model` 配置(默认 `qwen3.7-flash`),API key 取 `DASHSCOPE_API_KEY` 环境变量,其次 `agent.memory.api-key`。
- 未配置或 bean 构造失败 → **记忆写入能力整体禁用**,启动时打一条醒目 WARN,主对话流不受影响。此时不产出任何中期/长期记忆,而不是产出低质量记忆。
- 可选降级链 `agent.memory.fallback-to-main`(默认 `false`):开启后记忆模型失败可降级到主模型。降级目标同样是**配置的模型**,不违反本节约束。

**失败处理**

| 阶段 | 处理 |
| --- | --- |
| 模型不可用 | 跳过本轮记忆产出,WARN 一次(不刷屏),不落盘 |
| 调用异常 / 超时 | 退避重试 1 次;仍失败则将本轮 message 区间标记 `pending`,顺延到下一轮与下轮内容合并处理 |
| 输出解析失败 | 以 `repair` profile(附上原始输出与目标 schema)重试 1 次;仍失败则丢弃本轮产出并 WARN |
| 输出为空 / 全部字段无内容 | 视为合法结果,不写盘,不算失败 |

`pending` 区间累积上限默认 3 轮,超过则丢弃最旧区间,避免无限增长。

所有记忆产出调用均为**异步、非阻塞**,任何失败都不得打断主对话流。

### 7.6 提示词:按记忆文件类型分型配置

**提示词从 Java 内联常量外置为资源文件**,按记忆文件类型组织,一类文件一套提示词。调整摘要口径不需要改代码,也便于针对不同内容形态做差异化约束。

**目录**

```
src/main/resources/prompts/memory/
  short_term_compress.md      # 短期:单轮对话压缩
  mid_term_patch.md           # 中期:每轮增量补丁(四字段 JSON)
  mid_term_regen.md           # 中期:会话结束整体重生成(四字段 JSON)
  long_term_extract.md        # 长期:候选条目提取(四类归属 + 主题判定)
  long_term_topic_naming.md   # 长期:新主题文件的 <主题摘要> 命名
  index_summary.md            # 通用:生成 summary / topics / keywords
  repair.md                   # 通用:输出格式修复重试
```

由 `MemoryPromptRegistry` 在启动时加载。文件缺失则 fail-fast——记忆是可选能力,但提示词缺失属于部署错误,应当暴露而非静默降级。

**Profile 定义格式**

提示词文件本身也是 `frontmatter + 正文`:frontmatter 声明调用参数,正文分 `# system` 与 `# user` 两段,`user` 段用 `{占位符}` 承接运行时变量。

```markdown
---
profile: long_term_extract
maxOutputTokens: 1024
temperature: 0.2
variables: [conversation, existing_topics, existing_titles]
outputFormat: yaml
---

# system
（系统提示词正文）

# user
（用户提示词模板,含 {conversation} 等占位符）
```

**各 profile 的差异化要点**

| profile | 输入 | 输出 | 提示词侧重 |
| --- | --- | --- | --- |
| `short_term_compress` | 单轮原始对话(含 tool_calls) | 压缩后的对话文本 | **保留工具名、入参路径、错误码原文**,只压缩自然语言叙述;禁止丢失文件路径与标识符 |
| `mid_term_patch` | 既有四字段 + 本轮对话 | 四字段增量 JSON | 只输出增量,禁止复述既有条目;无增量的字段输出空数组;`crossSessionProgress` 的三态迁移要显式给出 |
| `mid_term_regen` | 整 session 对话 | 完整四字段 JSON | 结构化总结而非逐轮流水;`userFocus` 要提炼用户反复回到的点而非罗列话题;客观第三人称 |
| `long_term_extract` | 未总结轮次 + `existing_topics` + `existing_titles` | 候选条目 YAML | 强制归入 user/feedback/project/reference 之一;强制判定目标主题(命中已有 / 提议新建);附 evidence 原文;`importance >= 3` 才输出;**与 `existing_titles` 重复的不输出** |
| `long_term_topic_naming` | 该主题下的条目列表 | 一个短横线连接的主题摘要 | 长度上限内;语义聚焦;避免与 `existing_topics` 重名 |
| `index_summary` | 记忆文件正文 | summary + topics + keywords | summary ≤ 40 字;topics 3-5 个中文主题词;keywords 3-8 个小写英文标识符,驼峰与下划线需拆分 |
| `repair` | 原始输出 + 目标 schema | 修正后的输出 | **只做格式修正,禁止改写语义、禁止新增内容** |

传入 `existing_titles` 与 `existing_topics` 是本版新增:在**提取阶段**就让模型避开已有条目与已有主题,这是本版对重复条目的唯一手段(§7.3)。

**`long_term_extract` 输出格式**

字段直接对应 §7.4 的文件结构,免去二次加工:

```yaml
- category: feedback                       # user | feedback | project | reference
  topic: 001-spring-config                 # 命中的已有主题;新建则填 "NEW"
  topicProposal: 记忆模型隔离                # topic == NEW 时给出主题提议,否则留空
  title: 记忆模型必须独立配置 bean
  content: 记忆专用模型必须与主模型走独立 bean,不要共用同一个 ChatModel
  importance: 5
  pinned: true
  evidence: 'user: "记忆的模型要单独配,别跟主模型混"'
  reason: 共用会导致记忆调用挤占主对话配额
```

空列表 `[]` 是合法输出,表示本轮无值得记录的内容。

**候选条目处理流程**

1. 记忆模型输出候选 YAML 列表(空列表合法)。
2. CLI Renderer 呈现:类别 + 重要性 + 目标主题 + title + 是否常驻。
3. 用户逐条确认 / 拒绝;可改写目标主题(如「这条归到 005-git-工作流」)与 `pinned`。
4. 确认的条目按主题归入对应长期记忆文件;新主题则调用 `long_term_topic_naming` 命名并分配下一个可用序号。
5. 对受影响的文件调用 `index_summary` 刷新 `summary` / `topics` / `keywords`,更新内存评分缓存,并整体重渲染 `MEMORY.md`。

### 7.7 召回评分方案

**问题**:现有 `MemoryIndex.matchTopN` 只做子串匹配,且**匹配失败时返回最近 N 条**——等于"没找到相关的就硬塞几条上去"。这既浪费 token,又给模型注入误导性上下文。主题拆分后长期记忆文件数量还会增长,原先"全量加载"的做法同样不可持续。

**目标**:相关才召回,不相关就留空。

**召回池**

参与评分的候选单元:

- 全部长期记忆**主题文件**(以文件为评分单元),排除其中 `pinned: true` 的条目。
- 全部**非当前 session** 的 `mid-term.json`。

不参与评分、直接注入的:

- 所有 `pinned: true` 或 `importance = 5` 的长期条目。
- 当前 session 的 `mid-term.json`。
- 短期记忆最近 N 轮。

**评分公式**

```
score = 0.45 * lexical
      + 0.20 * importance / 5
      + 0.20 * recency
      + 0.15 * typeWeight
```

- `lexical`——query 与候选单元 `summary + topics + keywords + title` 的词项重叠度,按 IDF 加权后归一到 `[0,1]`。中文按二元切分,英文标识符按驼峰/下划线拆分后小写。**`lexical == 0` 时整体 score 直接判 0**,不给其他项抬分的机会——这是"不盲目召回"的关键闸门。
- `recency`——`exp(-Δdays / halfLife)`,基于 `updatedAt`,半衰期默认 14 天。
- `typeWeight`——`feedback 1.0 / project 0.9 / reference 0.7 / user 0.7 / session 0.5`。
- 权重、半衰期、各类型权重均可配置(§7.11)。

**门控规则**

1. `score < threshold`(默认 `0.35`)→ 丢弃。
2. 剩余候选按 score 降序取 TopN(默认 5)。
3. 累加 token 超过对应层配额则按 score 降序截断到配额内。
4. **允许结果为空**:全部低于阈值时,该层 `ContextEntry` 置空,不注入任何内容。这是正常状态,不是降级,不触发任何兜底。
5. `query` 为空(无用户输入的自动步骤)→ 跳过评分,只注入常驻部分。

**注入形态**

召回结果不是把整个文件塞进去,而是渲染为紧凑列表,给出 `title + summary + 路径`,让模型自行判断是否需要用工具读取全文:

```
# 召回记忆(2 条,阈值 0.35)
- [topic 0.82] 001-spring-config.md — Spring AI 与 DashScope 的模型配置约定
- [session 0.61] .agent/sessions/2026-07-30-002/mid-term.json — 上次会话完成了动态层拆分,遗留 TASK_PLAN 未接
```

`pinned` 条目单独成段展开全文,不显示分数。

**可观测性**

每次召回记录一条 DEBUG 日志:query 摘要、候选池大小、各候选 score、阈值、命中数。用于调参与回归验证。CLI 提供 `/memory why <query>` 打印评分明细,便于用户理解"为什么这条没被召回"。

**为什么不用小模型打分**

评分要在每轮装配时执行,用模型打分会给每轮增加一次固定延迟与费用,结果不稳定且难以回归测试。本地评分确定、可测、零额外开销。若后续发现词项召回不足(同义表述漏召),再在本地粗筛 TopK 之上叠加一层模型重排——评分框架已为此预留 `MemoryRecallScorer` 接口。

### 7.8 文件组织

```
{NNN-<主题摘要>.md}                                    # 项目根,长期记忆文件(按主题拆分,正文按 user/feedback/project/reference 四类分块)
{000-待归类.md}                                        # 项目根,主题判定失败的候选暂存(可选)
{MEMORY.md}                                            # 项目根,记忆索引目录页(MD + YAML frontmatter)
.agent/sessions/{sessionId}/short-term.json            # per-session,短期记忆(JSON,Spring AI Message 格式)
.agent/sessions/{sessionId}/tool-results/{id}.json     # per-session,大体积工具结果外置文件(按需产生)
.agent/sessions/{sessionId}/mid-term.json              # per-session,中期记忆(JSON,四字段 + 可评分元数据)
```

- 短期与中期记忆统一为 JSON:短期按 Spring AI Message 模型对齐,中期按四字段结构化。
- 长期记忆保留 MD + YAML frontmatter,按主题拆分为多个 `NNN-<主题摘要>.md`;序号递增分配,主题摘要由模型生成。
- `MEMORY.md` 是常驻索引目录页,收录全部记忆文件,不做 LRU 淘汰。
- 短期、中期、长期文件均与项目代码一起纳入版本管理。
- §7.12 / §7.13 暂不实现,因此**不产生** `.archive` 归档文件与 `memory-gc.log.jsonl`。

### 7.9 一致性与原子性

- 中期与长期记忆的写入采用"写临时文件 + rename",杜绝半写损坏;**每个长期记忆主题文件独立完成临时文件 + rename**。
- 短期记忆采用增量追加 + 对话结束时全量落盘;整体落盘用临时文件 + rename。
- 每次落盘后 fsync,保证崩溃可恢复。
- 长期记忆条目**只增不改**:内容修订视为新增条目 + 旧条目标 `deprecated: true`,天然规避并发写冲突。
- 启动时扫描 `NNN-*.md` 与 `.agent/sessions/*/mid-term.json` 重建**内存评分缓存**;单个文件解析失败则备份为 `.corrupted-{ts}` 并跳过,不影响其他文件。
- `MEMORY.md` 由内存缓存整体重渲染写出,而非增量追加;若索引与磁盘上的记忆文件不一致,**以磁盘文件为准**,索引可随时重建。
- 长期记忆的每次修改追加到独立 changelog,用于审计与回滚。

### 7.10 与现有组件的衔接

| 组件 | 现状 | 本版改动 |
| --- | --- | --- |
| `ContextBuilder.loadDynamicLayer` | `MEMORY_INDEX` 走 `matchTopN` 关键词匹配;`LONG_TERM` 全量加载 `Nico.md` | `LONG_TERM` = pinned / importance=5 条目常驻;`MEMORY_INDEX` 改为 `MemoryRecallScorer` 评分召回,**可为空** |
| `MemoryIndex` | 承载条目 + 关键词匹配 + LRU 20 | 降为 `MEMORY.md` 目录页读写;匹配逻辑移出到 `MemoryRecallScorer`;取消 LRU |
| `LongTermStore` | 读写单一 `Nico.md`,五分类 | 改为多主题文件读写(`NNN-<主题摘要>.md`),四分类,新增主题判定与序号分配 |
| `MidTermStore` | Markdown 五段正文 | 改为 JSON 四字段 + 可评分元数据头部 |
| `SessionMessageStore` | Markdown 逐段 message | 改为 JSON Message 序列,大工具结果外置 |
| `FlashMemorySummarizer` | 内联提示词 + 启发式兜底 | 提示词移到 `MemoryPromptRegistry`;**删除全部启发式路径**;新增退避重试与 `repair` 重试 |
| `LightweightChatModelConfig` | 失败返回 `null` 静默降级 | 失败时禁用记忆写入并 WARN;支持 `fallback-to-main` |
| `MemoryTurnHook` / `LongTermMaintainer` | 每轮 / 定时触发 | 触发时机不变;新增 `pending` 区间重试队列 |
| `ConversationCompressor` | 内联压缩提示词 | 改用 `short_term_compress` profile |
| 新增 `MemoryPromptRegistry` | — | 启动时加载 `prompts/memory/*.md`,按 profile 提供 system/user 模板与调用参数 |
| 新增 `MemoryRecallScorer` | — | 承载 §7.7 评分与门控,输出可为空列表 |
| `ToolResult.status + errorCode` | 结构化错误链 | 不变;连续失败或同类错误仍作为"教训型"中期片段来源 |

### 7.11 可配置项

```yaml
agent:
  memory:
    model: qwen3.7-flash          # 记忆专用模型
    api-key: ${DASHSCOPE_API_KEY}
    fallback-to-main: false       # 记忆模型失败是否降级主模型
    retry: 1                      # 调用失败重试次数
    pending-max-turns: 3          # pending 区间累积上限
    short-term:
      keep-rounds: 5              # 直接注入的最近轮数
      tool-result-inline-limit: 2KB   # 工具结果外置体积阈值
    long-term:
      seq-digits: 3               # 主题文件序号位数
      topic-name-max-length: 30   # 主题摘要最大长度
      topic-match-threshold: 0.85 # 低于此值视为新主题
    recall:
      threshold: 0.35             # 召回分数阈值
      top-n: 5                    # 最多召回条数
      half-life-days: 14          # recency 半衰期
      weights:
        lexical: 0.45
        importance: 0.20
        recency: 0.20
        type: 0.15
    session:
      idle-timeout-minutes: 10    # 空闲多久视为会话结束
```

以下配置项对应 §7.12 / §7.13,本版**不生效**,保留占位:合并数量阈值、合并时间阈值、去重相似度阈值、GC 调度周期、遗忘算法参数、退役条目处置方式、主题再切分/合并是否自动执行。

### 7.12 记忆合并与去重机制(暂不实现,保留设计)

> **本版不实现**。当前对重复条目的处理只有两道:提取时把 `existing_titles` / `existing_topics` 传给模型让其规避(§7.3),以及用户确认环节人工把关。下述完整机制待后续版本落地。

记忆不是只增不删——系统应在日常使用过程中持续做合并与去重,避免重复事实堆积与噪声累积。合并与去重针对**中期记忆各字段**与**长期记忆各主题文件**分别进行。

**触发节点**(中期与长期共用一套触发器)

- **数量触发**:中期四字段中任一字段条目数超过阈值(默认 20 条),或长期**单一主题文件**条目数超过阈值(默认 20 条,可配置)时触发。
- **时间触发**:自上次合并以来超过时间阈值(默认 7 天,可配置)时触发。
- **事件触发**:会话结束、用户显式 `/compact memory`、LLM 在提取候选时主动建议等。

**合并动作**(由配置的记忆模型驱动)

模型对一批"新日志"(本次待写入的候选 + 同主题文件已有条目)进行去噪,对每条已有条目判定以下动作:

- **ADD**:新候选为全新事实,与已有条目无重叠,直接追加写入。
- **UPDATE**:新候选是对已有条目的修正、细化或反例,覆写已有条目,保留 `updatedAt` 时间戳与 `supersedes` 历史指针。
- **MERGE**:新候选与已有条目部分重叠,融合为一条更完整的描述,旧条目标 deprecated。
- **IGNORE**:新候选已被现有条目覆盖、为噪声或与已有事实冲突,丢弃,不写入。
- **主题再切分**(长期特有):同一主题文件内条目语义分裂时,可提议拆分为两个新主题文件,自动分配新序号并迁移条目;原文件标 deprecated 并归档。

**去重判断**(每次保存时同步进行)

- 在新增候选入库前,与**同主题文件已有条目**做相似度比对(嵌入向量相似度 + 关键词重合度,综合得分)。
- 综合相似度超过阈值(默认 0.92,可配置)时,直接走 IGNORE / MERGE 而非 ADD。
- 去重检查与合并动作共用一次模型调用,不引入额外流程。

**作用域**

- 中期记忆的四字段各自独立合并;跨字段不合并。
- 长期记忆按主题文件独立合并;跨主题文件不强制合并,但允许模型提议主题再切分或跨主题合并。

**一致性要求**

- 合并写盘同样采用"写临时文件 + rename",每个主题文件独立完成;合并前先备份受影响文件,完成后再删除备份。
- 主题再切分 / 主题合并视为一次原子事务——先创建新文件 + 迁移条目,再标 deprecated 旧文件,全部成功才提交;任一步骤失败回滚到备份。

### 7.13 记忆淘汰机制(暂不实现,保留设计)

> **本版不实现**。当前记忆库只增不删,`MEMORY.md` 也取消了 LRU 20 限制。代价是记忆库随时间单调增长、召回池变大;缓解手段是 §7.7 的阈值门控——池子变大不会导致注入变多,只会让评分计算量线性增长,在条目数达到千级之前不构成问题。届时再引入本节机制。

合并解决的是「同类条目内部的新旧更替与重叠」,淘汰解决的是「长期库整体膨胀与低价值废料累积」。两者互补。

**驱动方式**

- 后台定时任务,默认每 24 小时跑一次(可配置),独立于对话流程,不阻塞用户交互。
- GC 启动时遍历所有长期记忆文件加载,在内存中完成评分与决策,最后逐文件落盘。

**淘汰对象**

- 主要针对**长期记忆**(所有 `NNN-<主题摘要>.md` 文件)。
- 中期记忆随 session 结束而冻结,不参与 GC;若用户长期不清理过期 session,可在 GC 阶段对超过保留时长的中期文件统一归档。

**遗忘算法**(综合三个维度计算"保留价值得分")

- **时间衰减**:自 `addedAt` 或 `lastAccessedAt` 起算,越久未访问分越低——指数衰减,半衰期可配置。
- **访问频次**:被召回引用的次数(由 §7.7 的召回日志统计并回写),频次越高分越高。
- **重要度得分**:条目的 `importance`(1-5),高分条目更难被淘汰。

**淘汰与压缩动作**

- 得分低于阈值(默认 1.5,可配置)的条目进入退役候选,二次确认后删除或归档到 `NNN-<主题摘要>.md.archive`。
- 高相似度(> 0.92)的同主题条目做**聚类压缩**:合并为一条概要 + 多个 `evidence` 引用。
- 已 deprecated 的旧条目在 GC 时直接清理。
- **主题合并**(长期特有):两个高度重叠的主题文件可合并为一个新主题,合并后分配新序号,旧文件标 deprecated 并归档。

**不淘汰约束**

- `importance = 5` 的关键红线 / 决策条目**永不淘汰**。
- `pinned: true` 的条目**永不淘汰**。
- 用户手动重命名或迁移过的文件默认视为受关注,GC 跳过退役建议(可配置)。

**审计**

- 每次 GC 产出独立 changelog(默认 `.agent/memory-gc.log.jsonl`),记录被淘汰 / 合并 / 退役 / 归档 / 主题合并的条目及原因,用于回滚与人工审计。
- 删除或归档条目前先写入 changelog,保证先有审计记录再有状态变更。

### 7.14 与原方案的差异

- **存储格式**:短期与中期记忆从 Markdown 改为 JSON——短期按 Spring AI Message 模型对齐(可直接复用为 `ChatMessage` 序列,工具调用按 `tool_call_id` 精确可提,大结果外置为独立文件),中期按四字段结构输出。长期记忆保留 MD + YAML frontmatter,但**从单一 `Nico.md` 拆分为按主题拆分的多文件**(`NNN-<主题摘要>.md`)。
- **可评分元数据**:所有参与召回的记忆文件统一新增 `summary` / `topics` / `keywords` / `importance` / `pinned`,作为召回评分的唯一数据来源(§7.4)。
- **长期记忆分类**:从「项目红线 / 编程风格约定 / 技术决策 / 依赖工具 / 重要约定」改为「user / feedback / project / reference」四类,聚焦于「agent 如何长期理解用户、协作、项目与外部信息」。
- **中期记忆字段**:从「session_goal / completed / decisions / lessons / pending_todos」五段模板改为「跨会话任务进度 / 会话总结 / 用户短期焦点 / 当前特定背景规则」四字段,更聚焦跨 session 复用价值。
- **摘要产出强制走配置模型**:删除全部启发式兜底路径;模型不可用即禁用记忆写入;新增退避重试、`repair` 格式修复重试、`pending` 区间顺延(§7.5)。
- **提示词分型外置**:从 Java 内联常量改为 `prompts/memory/*.md` 资源文件,按记忆文件类型分 7 个 profile,各自差异化约束;`long_term_extract` 新增 `existing_topics` / `existing_titles` 输入以在提取期规避重复(§7.6)。
- **召回评分**:从「关键词子串匹配 + 失败返回最近 N 条」改为「加权评分 + 阈值门控 + **允许为空**」;`lexical == 0` 直接判零分;长期记忆从「全量加载」改为「pinned 常驻 + 其余按主题文件评分召回」(§7.7)。
- **`MEMORY.md` 降级为目录页**:不再承载匹配逻辑,评分信号改由各记忆文件自身提供;**取消 LRU 20 淘汰**,收录全部记忆文件。
- **合并、去重、淘汰推迟**:§7.12 / §7.13 保留完整设计但本版不实现;相关归档文件与 GC 日志不产生,相关配置项占位不生效。

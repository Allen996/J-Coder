## 7. 记忆系统

### 7.1 重新定位

记忆不是"重启恢复对话",而是让 agent 在不同时间尺度上保留与复用上下文。分三层,**全部以 Markdown 文件本地存储**——文件头部 YAML frontmatter 记录 schema 版本、sessionId、创建时间等元数据,正文为 Markdown 结构化内容。便于用户直接检视、编辑、纳入版本管理;字段结构化便于程序解析;不引入 SQL 或任何外部依赖。

### 7.2 三层架构

短期记忆

- 粒度:单个会话的完整对话流。
- 内容:全部 user / assistant / tool-call 原始条目,按时间顺序追加。
- 形态:每个 session 一份独立 Markdown 文件 `.agent/sessions/{sessionId}/short-term.md`;头部 YAML frontmatter 记录元数据;正文每条 message 一个段,含 role / timestamp / content;tool_call 与 tool_response 邻段配对,共享 `message_id`。
- 加载语义:ContextBuilder 装配时**默认取最近 5 轮对话直接加入上下文**;超预算则递减轮数(4→3→2→1);1 轮仍超则对该轮做 LLM 摘要压缩。**不再使用滑窗淘汰**。
- 写入时机:对话过程中增量追加,对话结束时落盘完整文件。
- 生命周期:session 显式结束时冻结。

中期记忆

- 粒度:单个 Session 的整体总结。
- 来源:每轮对话结束后由轻量级 LLM 对本轮对话提取摘要;会话结束时整体重生成。
- 形态:每个 session 一份独立 Markdown 文件 `.agent/sessions/{sessionId}/mid-term.md`;头部 YAML frontmatter 记录元数据;正文固定结构包含:
  - `session_goal`:会话目标
  - `completed`:已完成事项列表
  - `decisions`:关键决策列表
  - `lessons`:教训列表
  - `pending_todos`:后续待办列表
- 生成方式:轻量级 LLM(flash 级别)在每轮对话结束后调用,LLM 提示词约束结构化 YAML 输出。
- 更新时机:每轮对话结束后增量更新;会话结束时整体重生成。
- 消费:`memory_index` 隐式按相关性匹配,默认返回最相关 5 条;ContextBuilder 装配时调用。

长期记忆

- 粒度:单个项目。
- 内容:项目的技术要求、目的、架构约定、关键决策、代码风格、依赖清单等"项目骨架信息"。
- 形态:项目根目录下唯一一份 Markdown 文件 `Nico.md`;头部 YAML frontmatter 记录元数据;正文按分类(`# 项目红线` / `# 编程风格约定` 等)用 Markdown 列表组织,经用户确认的候选条目以 YAML 代码块形式追加。
- 写入时机:**每轮对话结束后**由轻量级 LLM 对本轮对话进行总结提取候选条目,候选条目以结构化列表形式呈现给用户,经用户确认后追加写入 `Nico.md`。
- 消费:session 开始时从 `Nico.md` 全量加载进 prompt(项目骨架小,常驻可见)。
- 更新策略:追加式更新;旧条目可标注 deprecated 但不删除;支持用户用编辑器直接维护。
- LLM 提示词设计:见 §7.7。

记忆索引(`MEMORY.md`)

- 形态:项目根目录下唯一一份 Markdown 文件 `MEMORY.md`(头部 YAML frontmatter 记录元数据),作为 `memory_index` key 承载于动态层。
- 内容:每行格式 `<记忆文件路径> - <简介>`,如 `Nico.md - 项目骨架`、`.agent/sessions/2026-07-23-001/mid-term.yaml - 2026-07-23 会话 001 摘要`。
- 维护:每次产生记忆文件(短期、中期、长期)时同步更新索引。
- LRU 策略:仅保留最近 20 个记忆条目;过期记忆文件不删除,仅从索引移除。
- 加载:session 开始时加载;不存在时无需加载(视为空索引)。
- 消费:ContextBuilder 装配时隐式调用小模型智能匹配 `MEMORY.md` 中最相关 5 条记忆;匹配失败则降级为关键词匹配。

### 7.3 三层之间的桥梁

短期 → 中期

- 每轮对话结束后,由轻量级 LLM 对本轮对话提取摘要,合并进 `mid-term.yaml`
- 会话结束时整体重生成

中期 → 长期

- **不主动晋升**。用户在对话中通过"记住.../不要..."等触发语句表达,或轻量级 LLM 在每轮对话结束后提取的候选条目,经用户确认后才写入 `Nico.md`。

冲突解决

- 同一事实跨层出现:长期 > 中期 > 短期
- 同层多版本:保留全部并附时间戳,运行时取最新

### 7.4 文件组织

```
{Nico.md}                                              # 项目根,长期记忆(MD + YAML frontmatter)
{MEMORY.md}                                            # 项目根,记忆索引(MD + YAML frontmatter)
.agent/sessions/{sessionId}/short-term.md              # per-session,短期(MD + YAML frontmatter)
.agent/sessions/{sessionId}/mid-term.md                # per-session,中期(MD + YAML frontmatter)
```

- 所有记忆文件统一 Markdown 格式,头部 YAML frontmatter 记录 schema 版本、sessionId、创建时间等元数据
- `MEMORY.md` 是常驻索引,LRU 20 项
- 短期与中期文件与项目代码一起纳入版本管理(项目级 `.agent/sessions/`)

**Markdown 文件结构示例**:

```markdown
---
schema: 1
sessionId: 2026-07-23-001
createdAt: 2026-07-23T15:30:00
updatedAt: 2026-07-23T16:45:00
---

# 会话目标
修复 part3 上下文设计的三层划分问题

## 已完成事项
- 完成 part3 §6.3 短期记忆加载语义设计
- 完成 MEMORY.md 索引机制设计

## 关键决策
- 短期记忆加载采用"最近 5 轮递减"
- MEMORY.md 作为常驻索引不被压缩

## 教训
- 字典抽象对模型不可见,文档需说明渲染步骤

## 后续待办
- 设计 ContextBuilder 隐式匹配机制
```

### 7.5 一致性与原子性

- 中期与长期记忆的写入采用"写临时文件 + rename"模式,杜绝半写损坏
- 短期记忆采用 append-only 增量追加 + 对话结束时全量落盘;增量写盘可用 append-only,整体落盘用临时文件 + rename
- 每次落盘后 fsync,保证崩溃可恢复
- 启动时扫描磁盘重建内存索引;解析失败自动备份为损坏文件并跳过,不影响其他文件
- 长期记忆的每次修改追加到独立 changelog,用于审计与回滚
- `MEMORY.md` 在每次记忆文件产生时同步更新;LRU 淘汰仅从索引移除,文件保留

### 7.6 与现有组件的衔接

- `memory_index`(`MEMORY.md`):常驻动态层,压缩阶段不动;ContextBuilder 装配时隐式调用小模型匹配最相关 5 条记忆,匹配失败降级关键词
- 短期:ContextBuilder 按"最近 5 轮递减 + 单轮 LLM 压缩"装配,不再用滑窗
- 中期:由 `memory_index` 索引按相关性检索后注入
- 长期:session 开始时从 `Nico.md` 全量加载
- 总结:每轮对话结束后由轻量级 LLM(flash 级别)调用,既生成中期摘要又提取长期候选
- 现有 `ToolResult.status + errorCode` 结构化错误链作为短期观察事件来源;连续失败或同类错误可触发"教训型"中期片段追加

### 7.7 长期记忆提取提示词设计

每轮对话结束后调用轻量级 LLM 提取候选长期记忆条目,提示词设计如下。

**System 提示词**:

```
你是一个项目记忆分析师。你的任务是审查一段完整的开发会话对话,从中提取应该永久记录到项目骨架(Nico.md)的关键信息。

## Nico.md 收录范围

只记录以下类型的信息:
1. 项目红线: 禁止事项(不要 X、不要 Y)
2. 编程风格约定: 代码风格、命名约定、文件组织规则
3. 关键技术决策: 已确定的技术选型、架构决策
4. 依赖与工具: 引入的新依赖、工具配置变更
5. 重要约定: 团队约定、协作规则

不要记录:
- 一次性任务细节
- 临时调试信息
- 个人偏好(除非是项目级约定)
- 已有内容的简单重复

## 输出格式

以 YAML 列表输出候选记忆条目,每条包含:
- category: red_line | coding_style | decision | dependency | convention
- content: 精炼描述,1-2 句话
- importance: 1-5 的整数评分
- evidence: 对话中相关原文引用,1-2 句话
- reason: 为什么值得记录,1 句话

如果没有值得记录的内容,返回空列表 `[]`。

## 重要性评分标准

- 5: 关键红线/决策,违反会导致严重后果
- 4: 重要约定,团队普遍遵守
- 3: 一般性建议,有参考价值
- 2: 个例,价值有限
- 1: 边缘,基本无用

仅输出 importance >= 3 的条目。
```

**User 提示词**(占位符替换):

```
以下是本轮对话的完整内容,请按上述规则提取候选长期记忆条目:

{conversation}
```

**输出示例**:

```yaml
- category: red_line
  content: 不要直接修改生成的 entity 类,改用 builder 模式
  importance: 5
  evidence: User: "我们不能改 entity,得用 builder"
  reason: 违反会导致与数据库 schema 不一致
- category: coding_style
  content: 所有公共 API 必须有单元测试
  importance: 4
  evidence: User: "记住,所有公共 API 都要写单测"
  reason: 团队基本约定
```

**`Nico.md` 写入示例**(YAML 代码块嵌入 Markdown):

```markdown
---
schema: 1
projectName: super-biz-agent
updatedAt: 2026-07-23T16:45:00
---

# 项目红线

```yaml
- category: red_line
  content: 不要直接修改生成的 entity 类,改用 builder 模式
  importance: 5
  addedAt: 2026-07-23T16:45:00
```

# 编程风格约定

```yaml
- category: coding_style
  content: 所有公共 API 必须有单元测试
  importance: 4
  addedAt: 2026-07-23T16:45:00
```
```

**候选条目处理流程**:

1. 轻量级 LLM 输出 YAML 候选列表
2. CLI Renderer 以简洁形式呈现给用户(如分类 + 重要性 + content)
3. 用户逐条确认或拒绝
4. 用户确认的条目由 agent 以 YAML 代码块形式追加写入 `Nico.md` 的对应分类段落
5. 同时更新 `MEMORY.md` 索引(增加一行)

### 7.8 可配置项

- 短期记忆加载轮数(默认 5)
- 中期记忆使用的 LLM 级别(flash / mini 等)
- 长期记忆提取使用的 LLM 级别(flash / mini 等)
- 中期记忆保留时长
- 会话结束的空闲超时阈值(默认 10 分钟)
- `MEMORY.md` LRU 条目数(默认 20)

### 7.9 与原方案的差异

- 全部使用 Markdown 文件(头部 YAML frontmatter 记录元数据),LLM 输出的结构化数据以 YAML 代码块嵌入 MD 正文——兼顾人工编辑与程序解析
- 短期记忆从"滑窗淘汰 + 占位符"改为"最近 5 轮递减 + LLM 单轮压缩"
- 中期记忆从"per-session 5 段模板"明确为"Session 整体总结,由每轮对话后的轻量级 LLM 增量生成,会话结束时整体重生成"
- 长期记忆从"用户显式指令触发"扩展为"每轮对话结束后 LLM 自动提取候选 + 用户确认"
- `MEMORY.md` 进入动态层作为常驻索引,LRU 20,过期文件保留
- 短期/中期存储位置不变,格式统一为 MD + YAML frontmatter
# Prompt 架构优化方案（Static 净化 + Skill 化 + 模板版本化）

> 状态：设计稿。本期目标是把 prompt 切分成 **always-on + on-need + session-state** 三段,
> 让 Static Layer 真正稳定以命中 prefix cache,把"按场景需要"的 CoT / 调试 / 重构 / 验证
> 思维链抽象成 Skill 按需注入,并把全部 prompt 收口到同一套模板 + frontmatter。

---

## 1. 背景与目标

### 1.1 当前问题

J-Coder 的 ContextBuilder 当前把 4 个 key 都放在 Static Layer:

```
Static Layer
├─ ROLE_DEFINITION       ← 真静态(合理)
├─ TOOL_LIST             ← 基本静态(合理)
├─ CODE_WRITING_COT      ← 伪静态(只在写代码场景需要,却常驻)
└─ RUNTIME_META          ← 伪静态(时间 + 模型,跨 turn / 跨 execution 都会变)
```

代码引用 ([ContextBuilder.java:262-269](src/main/java/org/example/agent/context/builder/ContextBuilder.java#L262-L269)):

```java
String meta = renderRuntimeMeta(task);   // 包含 Instant.now()
staticLayer.put(ContextKey.RUNTIME_META, ...
        .sourceRef("static://runtime_meta")
        .build());
```

由此引发 3 个具体问题:

1. **前缀缓存反复失效**:`renderRuntimeMeta` 把当前时间塞进 Static,**每 turn 都让 KV cache 段重算**,
   即使工具清单与角色定义稳定,Static 也无法命中。
2. **token 浪费**:CoT 常驻对纯 chat / read-only 会话不产生价值,却要每次付账。
3. **扩展性差**:加新 CoT = 加 Static key,Static 越来越胖;无法独立版本化 / A/B。
4. **模型名位置错位**:跨 execution 可能切到 flash / plus,**放在 Static 等于假装不变**。

### 1.2 设计目标

1. Static Layer 只装 **任何场景都必需** 的稳定内容(角色 + 工具描述)。
2. CoT / Debug / Refactor / Verify 等"按场景需要"的思维链抽象为 **Skill**,放进 Dynamic 层,
   由意图标签 / step 类型 / 最近工具调用触发。
3. RUNTIME_META 拆成 **Static 部分**(工作目录、OS、构建工具——session 内稳定)和
   **Dynamic 部分**(时间、当前 executionId、当前模型)。
4. 全部 prompt 收口到 **同一套 frontmatter + Markdown 模板**,统一 maxOutputTokens / temperature / variables / schemaVersion。
5. **PromptVersionRegistry** 提供 variant 灰度与 `PromptDumpObserver` 集成,实现 prompt 可回溯。

### 1.3 收益量化

按假设场景粗算(系统提示 1500 token,Static 占 800、Dynamic 占 700):

| 方案 | 第 2 turn 起 Static 命中 | chat-only session 节省 |
|---|---|---|
| 现状(时间 / CoT 在 Static) | 0(时间每 turn 变) | 0 |
| 优化后(2 + 拆分) | 100%(Static 段稳命中) | 每 turn 省 200-300 token |

一次会话 30 轮累计省 **30 × 250 = 7500 token**;对纯 chat / read-only session,节省更显著。

---

## 2. 总体架构(三个 prompt 段)

把 prompt 从原来的两层(Static + Dynamic)切成 **三段**:

```
[Always-on] Static Layer(任何场景都必需)
   ├─ ROLE_DEFINITION       角色 + 硬约束 + 风格
   └─ TOOL_LIST             工具描述 + 风险 + 可逆 + 超时

────────── Layer Separator ──────────

[On-need] Skill Slots(Dynamic 内部,按触发条件注入)
   ├─ code_writing_cot      WRITE_PROJECT 触发
   ├─ debug_cot             出现 error / exception 触发
   ├─ refactor_cot          WRITE_PROJECT + "重构 / 抽 / 拆" 语义触发
   ├─ verify_cot            当前 SubTask = VERIFY 触发
   ├─ rollback_recovery_cot 最近出现 RollbackEvent 触发
   └─ plan_execution_cot    有 active TaskPlan 触发

[Session-state] Dynamic Layer(每 turn 可能变)
   ├─ RUNTIME_META_DYNAMIC  当前时间 / 当前 executionId / 当前模型
   ├─ TASK_PLAN             active 计划的当前 SubTask / 依赖 / 进度
   ├─ MID_TERM              session 压缩摘要
   ├─ LONG_TERM_PINNED      importance=5 常驻
   ├─ MEMORY_INDEX          可召回主题列表
   └─ MESSAGES              历史 5 轮 + 压缩结果 + 当前 user
```

---

## 3. Static Layer 净化

### 3.1 改后的 Static

| Key | 内容 | 生命周期 | 来源 |
|---|---|---|---|
| `ROLE_DEFINITION` | 角色 + 工作约束 + 风格 + 上下文纪律 | 启动时 + /load 时刷新 | `prompts/agent/role_base.md` |
| `TOOL_LIST` | 工具清单 + 描述 + 风险 + 超时 + 缓存 | /load 时刷新 + 工具注册变化 | `prompts/tools/tool_descriptors.md` + `ToolDescriptorRegistry` |

`CODE_WRITING_COT` 与 `RUNTIME_META` 从 Static 中**整体移除**。

### 3.2 `RUNTIME_META` 拆分方案

| 新 Key | 层 | 内容 |
|---|---|---|
| `RUNTIME_META_STATIC` | Static | session 内基本不变的运行环境:工作目录绝对路径、OS、构建工具、Java 版本、默认超时配置、CI 标记 |
| `RUNTIME_META_DYNAMIC` | Dynamic | 当前时间(可选)、当前 executionId、当前模型、active sessionId、active planId、当前 step / 总步数 |

#### RUNTIME_META_STATIC 渲染示例

```markdown
## RUNTIME_META_STATIC
- 工作目录: /Users/you/projects/J-Coder
- Git 分支: eval/test1 (clean, ahead 0)
- 构建工具: Maven 3.9.6
- JDK: Java 17 (Temurin)
- 操作系统: macOS 14.5 (darwin/arm64)
- 默认工具超时: 30s(可在 cli.tool.timeout 调整)
- CI 模式: false
```

> 这些字段在 session 内基本不变,适合 Static;真正影响模型决策(选 `\` 还是 `/`、`mvn` 还是 `npm`)。

#### RUNTIME_META_DYNAMIC 渲染示例

```markdown
## RUNTIME_META_DYNAMIC
- 当前时间: 2026-08-19 14:30:01 (Asia/Shanghai)
- 当前 executionId: exec-20260819-143001-7c3f
- 当前模型: qwen3.7-plus
- active sessionId: sess-20260819-143001
- active planId: plan-002 (subtask 3/7 in progress)
- 当前 step: 12 / 50
```

### 3.3 改动文件

| 文件 | 改动 |
|---|---|
| `ContextKey.java` | 删除 `CODE_WRITING_COT`、`RUNTIME_META`;新增 `RUNTIME_META_STATIC`、`RUNTIME_META_DYNAMIC`;新增 Skill 系列 key(动态生成,见第 4 节) |
| `StaticLayer.java` | `declaredKeys()` 改为只返回 `ROLE_DEFINITION` 与 `TOOL_LIST` |
| `ContextBuilder.java` | `renderRuntimeMeta` 拆成 `renderRuntimeMetaStatic(task)` 与 `renderRuntimeMetaDynamic(task)`;`loadStaticLayer` 不再填充 COT 与原 RUNTIME_META |
| `loadDynamicLayer` | 在最前面填充 `RUNTIME_META_DYNAMIC` + Skill slots |
| `renderToolListText` | 升级为 `renderToolListText(task)`,读取 `ToolDescriptorRegistry` 输出带元信息的工具清单 |

---

## 4. Skill 抽象与按需注入

### 4.1 Skill 契约

每个 Skill = 一个 Markdown 文件 + frontmatter,与现有 memory 模板同形态。

```yaml
---
profile: code_writing_cot
trigger:
  intents: [WRITE_PROJECT]        # L1 标签匹配
  steps: [ACTION, OBSERVATION]    # 当前 step 类型匹配
  tools: [edit_file, write_file]   # 最近工具名匹配
  recentObservationContains: []   # 最近 observation 关键词匹配
priority: 10                       # 多个 skill 同时触发时的拼接顺序
ttl: 999                           # 激活后多少步内有效
excludes: []                       # 互斥的 skill profile
schemaVersion: 1
---
# Code Writing Chain-of-Thought
1. 先理解用户意图(不要急着动手)
2. 用 search/read 工具探查现有代码与上下文
3. 设计最小可工作改动,必要时列出方案
4. 实施改动,遵循项目的风格约定(参考 long_term 记忆)
5. 自我验证:编译 / 测试 / 边界检查
6. 总结:做了什么 / 为什么 / 后续可选优化
```

### 4.2 Java 侧抽象

`org.example.agent.context.skill` 包新增:

```java
public interface Skill {
    String profile();
    SkillTrigger trigger();
    int priority();
    int ttl();
    boolean shouldActivate(SkillContext ctx);
    String render(SkillContext ctx);   // 模板变量替换
}

public record SkillTrigger(
    List<IntentLabel> intents,
    List<StepKind> steps,
    List<String> tools,
    List<String> recentObservationContains
) {}

public class SkillContext {
    IntentLabel primaryIntent;       // L1 标签(由 IntentContext 提供)
    int currentStep;
    StepKind lastStepKind;
    String lastToolName;
    String lastObservation;          // 截断后 < 500 字符
    boolean hasActivePlan;
    int lastErrorStep = -1;          // 最近一次 error step,用于 ttl 控制
    List<String> recentKinds;        // 最近 5 步 StepKind,用于模式识别
    Map<String, Object> variables;   // 模板变量
}
```

`SkillRegistry`:

```java
@Component
public class SkillRegistry {
    private final List<Skill> skills;

    public List<Skill> collect(SkillContext ctx) {
        // 1. 按 trigger 过滤激活候选
        // 2. 应用 excludes 互斥
        // 3. 按 ttl 过滤已过期
        // 4. 按 priority 升序排序
        return skills.stream()
                .filter(s -> s.shouldActivate(ctx))
                .filter(s -> !isExpired(s, ctx))
                .sorted(Comparator.comparingInt(Skill::priority))
                .toList();
    }

    public Map<String, ContextEntry> renderAll(SkillContext ctx) {
        // 为每个激活的 skill 渲染为 Dynamic 层 entry
    }
}
```

### 4.3 注入位置

Dynamic 层最前(Static 之后,`RUNTIME_META_DYNAMIC` 之前):

```
[Static Layer separator]
[Skill: code_writing_cot] ← priority=10
[Skill: refactor_cot] ← priority=20(若同时激活,按 priority 顺序)
────────── Layer Separator ──────────
[RUNTIME_META_DYNAMIC]
[TASK_PLAN]
[MID_TERM]
...
```

理由:模型先看到 Skill 思维链,再看到当前运行时与历史,**保证 skill 的指导语义优先于历史经验**。

### 4.4 首期 Skill 清单

| Profile | Trigger | priority | ttl |
|---|---|---|---|
| `code_writing_cot` | `WRITE_PROJECT` 或最近工具 ∈ {edit_file, write_file} | 10 | 5 |
| `debug_cot` | 最近 observation 含 `error` / `exception` / `failed` | 20 | 3 |
| `refactor_cot` | `WRITE_PROJECT` 且最近工具含 `refactor` 或 user 文本含"重构/抽/拆" | 15 | 5 |
| `verify_cot` | 当前 SubTask.type = VERIFY | 30 | 3 |
| `rollback_recovery_cot` | 最近 5 步内有 `RollbackEvent` | 25 | 2 |
| `plan_execution_cot` | `hasActivePlan == true` | 5 | 999 |

### 4.5 改动文件

| 文件 | 改动 |
|---|---|
| 新增 `prompts/agent/skills/*.md` 6 份 | 每份一个 skill 的 YAML + Markdown |
| 新增 `context/skill/Skill.java` | Skill 接口 |
| 新增 `context/skill/SkillTrigger.java` | trigger record |
| 新增 `context/skill/SkillContext.java` | 运行时上下文 |
| 新增 `context/skill/SkillRegistry.java` | 注册 + 激活 + 渲染 |
| 新增 `context/skill/impl/CodeWritingSkill.java` 等 6 个实现 | 把 Markdown 解析为 Skill |
| `ContextBuilder.java` | `loadDynamicLayer` 头部插入 skill 渲染 |

---

## 5. 模板版本化(PromptVersionRegistry)

### 5.1 目标

- 统一 frontmatter 6 字段:`profile / maxOutputTokens / temperature / variables / outputFormat / schemaVersion`。
- 支持 **variant 灰度**:同一 profile 可注册多个变体(如 `default` / `v2-emphasize-revert`),运行时按配置切换。
- `PromptDumpObserver` 记录 `profile / variant / sha256`,任何一次回复都能复现 prompt。

### 5.2 接口设计

```java
public interface PromptVersionRegistry {
    /** 按 profile + 当前 active variant 渲染。 */
    String render(String profile, Map<String, Object> vars);

    /** 取 prompt 的执行参数(maxOutputTokens / temperature / outputFormat)。 */
    PromptProfile profile(String profile);

    /** 注册一个 variant,path 是 resources 下的模板路径。 */
    void registerVariant(String profile, String variantTag, String path);

    /** 设置当前生效的 variant(可热更新)。 */
    void setActiveVariant(String profile, String variantTag);

    /** 当前生效的 variant 标签。 */
    String activeVariant(String profile);

    /** 取模板的 sha256,用于 dump。 */
    String sha256(String profile);
}

public record PromptProfile(
    String profile,
    int maxOutputTokens,
    double temperature,
    OutputFormat outputFormat,    // TEXT | JSON | YAML
    int schemaVersion,
    String variantTag
) {}
```

### 5.3 与现有组件的接入

| 组件 | 改造点 |
|---|---|
| `MemoryPromptRegistry` | 重构为 `PromptVersionRegistry` 的特化,复用渲染逻辑 |
| `StaticLayer.initDefaults` | 改用 `PromptVersionRegistry.render("role_base", ...)` |
| `IntentPrompter` | L1 提示文本改用 `PromptVersionRegistry` |
| `TaskPlanContextAssembler` | 计划上下文改用 `PromptVersionRegistry.render("plan_context", ...)` |
| `MemoryModelGateway.callRaw` | 内部接 `PromptVersionRegistry` |
| `PromptDumpObserver` | dump 时记录 `profile / variant / sha256 / resolvedVars` |

### 5.4 改动文件

| 文件 | 改动 |
|---|---|
| 新增 `context/prompt/PromptVersionRegistry.java` | 接口 |
| 新增 `context/prompt/PromptProfile.java` | record |
| 新增 `context/prompt/impl/DefaultPromptVersionRegistry.java` | 基于 classpath 扫描 + frontmatter 解析 |
| `context/memory/MemoryPromptRegistry.java` | 改造为薄包装,内部调 `PromptVersionRegistry` |
| `context/observability/PromptDumpObserver.java` | dump 时加 profile / variant / sha256 |

---

## 6. L1 驱动的 Role Patch(场景化系统提示)

### 6.1 现状

`ROLE_DEFINITION` 当前是单一文本(参见 [ContextBuilder.java:289-293](src/main/java/org/example/agent/context/builder/ContextBuilder.java#L289-L293)):

```java
public static String renderRoleDefinition() {
    return "你是一个专业的智能助手，能调用工具回答用户问题。\n"
         + "风格：严谨、客观、可追溯。\n"
         + "能力：阅读项目代码、搜索信息、修改文件、执行命令、调用工具。";
}
```

这种"通用身份"导致模型在不同意图下行为同质化,不能针对场景自我约束。

### 6.2 方案

把 `ROLE_DEFINITION` 拆成 **base + patch**:

```
ROLE_DEFINITION(Static)
   = role_base.md(常驻)
   + role_<intent>.md patch(按 L1 label 拼接到尾部,长度 ≤ 200 token)
```

L1 完成 → `IntentContext.primaryLabel()` 已知 → 在 Dynamic 层插入 `[INTENT_PATCH]` 块,
长度硬限 ≤ 200 token,不挤占主预算。

#### Patch 清单

| L1 Label | Patch 要点 |
|---|---|
| `READ_CODE` | 优先 read_file / grep_search / list_dir;不调用写工具;回答末尾给文件:行号 |
| `WRITE_PROJECT` | 先 read 再改;改动 ≤ 50 行;改完必须自检或进 VERIFY |
| `RUN_COMMAND` | 单条命令;先用 cat/head 看可执行性;危险命令不绕过 |
| `PLANNING` | 走 create_plan,分解 3-7 子任务,VERIFY 类必须存在 |
| `CHAT_QA` | 不调用工具,纯解释;引用 long_term 或文档 |
| `OFF_TOPIC` | 一行致歉 + 引导回正题 |

### 6.3 改动文件

| 文件 | 改动 |
|---|---|
| `prompts/agent/role_base.md` | 新增,包含通用身份 + 安全 + 风格 |
| `prompts/agent/role_read_code.md` ~ `role_off_topic.md` | 6 份 patch |
| `ContextBuilder.java` | `loadDynamicLayer` 在 `RUNTIME_META_DYNAMIC` 之后插入 `[INTENT_PATCH]` |
| `IntentContext` | 已提供 `primaryLabel()`,复用 |

---

## 7. 错误反馈模板(ToolResponseMessage 文本规整)

### 7.1 现状

`ToolGateway` 返回 `ToolResult` / `ToolError` 后,ReActLoop 直接拼字符串塞回 model,
模型经常**看不懂错误模式**。

### 7.2 方案

统一错误反馈模板:

```markdown
# ERROR / {errorCode}
tool: {toolName}
recoverable: {true|false}
retryable: {true|false}
rollback_available: {true|false}

## cause
{shortReason}

## observed
{keyOutputOrStack}

## suggested_next_step
- {suggestion 1}
- {suggestion 2}
```

- `recoverable=false` 让模型不再盲目重试;
- `suggested_next_step` 由 `FailureClassifier` 在分类时给出;
- 长度受 frontmatter 的 maxOutputTokens 控制。

### 7.3 改动文件

| 文件 | 改动 |
|---|---|
| `prompts/tools/error_feedback.md` | 新增模板 |
| `ToolGateway.java` | 错误返回改走模板渲染 |
| `FailureClassifier.java` | 增加 `suggestedNextStep` 字段 |

---

## 8. 落地路径与工作量

按 P0 → P3 顺序,每步可独立验证:

| 优先级 | 改动 | 影响范围 | 工作量 |
|---|---|---|---|
| **P0** | `RUNTIME_META` 拆分 + 删除 `CODE_WRITING_COT` 从 Static | 性能 + 缓存 | 1-2 天 |
| **P0** | `PromptVersionRegistry` 骨架 + frontmatter 解析 | 全局收益 | 1-2 天 |
| **P0** | `TOOL_LIST` 渲染升级(带描述/风险/超时) | 减少乱调工具 | 0.5 天 |
| **P1** | Skill 抽象 + `code_writing_cot` 一个端到端跑通 | Dynamic 按需 | 1.5 天 |
| **P1** | L1 驱动的 Role Patch | 场景化行为 | 1 天 |
| **P1** | 错误反馈模板 + suggested_next_step | 自纠率 | 0.5 天 |
| **P2** | 补全其余 5 个 Skill | 完整覆盖 | 1 天 |
| **P2** | 记忆 prompt frontmatter 统一化 | 长会话质量 | 1 天 |
| **P2** | Task Plan 上下文走模板 | 子任务推进 | 0.5 天 |
| **P3** | variant A/B + sha256 记录 | 工程化 | 0.5 天 |

---

## 9. 测试与验收

### 9.1 单元测试

| 模块 | 测试 |
|---|---|
| `PromptVersionRegistry` | frontmatter 解析、变量替换、variant 切换、sha256 稳定 |
| `SkillRegistry` | trigger 匹配、excludes 互斥、ttl 过期、priority 排序 |
| `ContextBuilder` | Static 2 key;Dynamic 含 RUNTIME_META_DYNAMIC + Skill + memory |
| `renderToolListText` | 含描述 / 风险 / 超时 / 缓存字段 |

### 9.2 集成测试

| 场景 | 期望 |
|---|---|
| 启动后第一次 prompt | Static 只有 role + tool;时间在 Dynamic |
| 第二次 turn | prefix cache 对 Static 段命中,token 计费下降 |
| L1=WRITE_PROJECT session | `code_writing_cot` skill 被注入 |
| L1=CHAT_QA session | 无 skill 注入,prompt 更短 |
| VERIFY 子任务触发 | `verify_cot` skill 被注入 |

### 9.3 回归对比

- 同一批 eval 用例,优化前 vs 优化后的 token 消耗、首包延迟、TTFT、准确率。
- 重点对比 **Static 段的 cache 命中率**(通过 prompt dump 验证 sha256 跨 turn 一致)。

---

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Skill 触发逻辑写错,导致 CoT 不该出现时出现 | 单元测试覆盖 trigger 矩阵;`/context` 命令可视化当前激活 skill |
| `PromptVersionRegistry` 引入新解析路径,模板渲染变慢 | 渲染结果加 LRU 缓存;关键路径(主 agent)预热 |
| 时间从 Static 删除后,模型无法回答"今天日期" | 该场景属 `CHAT_QA`,允许调 `execute_command date` 或注入到 user 消息 |
| variant 灰度切换引入不一致 | 切换时强制下一次 execution 重新 `freeze()`,不留半态 |
| L1 label 错(误判为 WRITE_PROJECT)导致 skill 错注 | L1 有 fallback 策略:`cli.intent.fallback.default-label=OFF_TOPIC` 时不挂 skill |

---

## 11. 后续扩展

- **Skill 组合模式**:把多个 skill 拼成"剧本"(scenario),例如 `bug_fix_playbook = debug_cot + verify_cot + rollback_recovery_cot`。
- **Skill 学习**:根据 eval 结果自动调 trigger 阈值,使 CoT 在"高收益场景"自动出现、"低收益场景"自动隐藏。
- **跨 session skill 持久化**:高频使用的 skill 组合落到 long-term memory,新 session 启动时自动预激活。

---

> 完稿。请按 P0 → P3 顺序逐步落地,每步独立可验证。
---
profile: repair
maxOutputTokens: 1024
temperature: 0.0
variables: [raw_output, target_schema]
outputFormat: text
---

# system

你的任务是修复一段 LLM 输出的格式问题,使其严格匹配给定的目标 schema。

硬性约束:
  - 只做格式修正(字段名、包裹符、引号、数组 vs 对象、缺失字段默认值)
  - 严禁改写语义、严禁新增内容、严禁删减有效字段
  - 不输出解释,直接给修正后的完整产物

# user

【目标 schema】

{target_schema}

【原始输出】

{raw_output}

请输出修正后的内容。
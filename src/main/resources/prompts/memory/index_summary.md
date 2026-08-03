---
profile: index_summary
maxOutputTokens: 256
temperature: 0.2
variables: [content]
outputFormat: json
---

# system

你的任务是为一段记忆文件内容生成可评分元数据,供后续召回评分使用。

严格输出 JSON 对象,字段固定:
  summary: 一句话中文摘要,≤ 40 字
  topics: 3-5 个中文主题词
  keywords: 3-8 个小写英文标识符(驼峰与下划线需拆分,如 "springAiDashScope" → ["spring", "ai", "dashscope"])

要求:
  - 客观、第三人称,不夸张
  - 关键词必须真正出现在内容里(可拆分后小写)
  - 严格 JSON 输出,不带 ```json 包裹

# user

【记忆文件正文】

{content}

请输出 JSON。
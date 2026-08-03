---
profile: long_term_topic_naming
maxOutputTokens: 128
temperature: 0.2
variables: [entries, existing_topics]
outputFormat: text
---

# system

你的任务是为一个新主题文件命名。

输入是该主题下已有候选条目的列表;输出是一段简短的主题摘要(小写、短横线连接,可含中文,长度 ≤ 30 字符)。

要求:
  - 语义聚焦(不超范围)
  - 避免与现有主题列表重复
  - 简洁,直接给结果文本,不要加前缀/解释

# user

【已有主题列表】

{existing_topics}

【该主题下的条目】

{entries}

请输出主题摘要。
# 意图识别验证集（Intent Golden Set）

> 对齐 `design/intent.md` §10 的承诺,把"100 条黄金集"真正落地,并扩充到 300 条以支撑
> 6 类 per-class 精度统计。同时建立 L2 工具门控的决策标注集。

## 文件

| 文件 | 说明 |
| --- | --- |
| `golden.jsonl` | L1 意图分类的标注集(300 条,6 类均衡 + 难度分层 + 语言分层)。 |
| `golden.schema.md` | 字段含义、标注规范、切分规则。 |
| `golden.splits.json` | 官方 train/dev/test 切分,防止数据泄漏。 |
| `l2-decisions.jsonl` | L2 工具语义门控标注集(150 条),测误拦/漏放率。 |
| `l2-decisions.schema.md` | L2 字段含义与评判规则。 |
| `run-eval.ps1` | 评测脚本骨架(后续 PR 接入真实 Spring 上下文;当前 PR 只交付数据集)。 |
| `validate.ps1` / `stats.ps1` / `stats-l2.ps1` / `generate-splits.ps1` | 数据集维护工具(JSON 校验、分布统计、再切分)。 |

## 设计依据

- 现状:仓库里只有 6 条"输入 → 期望标签"单测样本,且全是规范说法,OFF_TOPIC 0 覆盖,
  关键词词典还有歧义("实现"同时进 READ/WRITE)。详见上一轮分析报告。
- 设计目标:每个 IntentLabel ≥ 40 条;覆盖 easy/medium/hard 三个难度;
  语言分布 zh:en:mixed ≈ 70:15:15;对抗/注入样本独立成池。
- **第三阶段**:`OFF_TOPIC` 类别已删除,56 条历史 OFF_TOPIC 样本(问候/拒绝/注入/跑题)
  统一归入 `CHAT_QA`,由关键词层 + 响应模板分流处理。
- 门禁:对齐设计稿 §10 —— L1 top-1 ≥ 85%,L2 误拦 < 5%。

## 使用方式

1. 用 `golden.splits.json` 取 `test` 切片跑 `IntentGate.classify`,**不要**拿同一批数据
   既调参又报准确率(见 `golden.schema.md` §"切分与防泄漏")。
2. 报告指标: top-1 accuracy / macro-F1 / 混淆矩阵 / per-class precision+recall /
   fallback 率 / tier 分布 / L2 误拦率 / L2 漏放率。
3. 门禁接入 `mvn verify` 或 CI,top-1 < 85% 或 L2 误拦 > 5% 即失败。
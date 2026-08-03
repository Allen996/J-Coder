# J-Coder Compression Eval — Summary

char/token 估算系数 = 3.5；ratio = context_before / context_after

| mode | rounds | triggered | trigger_share | avg_ratio | max_ratio | total_chars | est_tokens |
|------|-------:|---------:|--------------:|----------:|----------:|------------:|-----------:|
| A_on | 10 | 3 | 0.30 | 1.150 | 1.500 | 198422 | 56692 |
| B_off | 0 | 0 | 0.00 | 1.000 | 1.000 | 0 | 0 |
| C_truncate | 0 | 0 | 0.00 | 1.000 | 1.000 | 0 | 0 |

## Metric interpretation

1. **avg_compression_ratio**：压缩机制越激进、平均 ratio 越高；trigger_share过低说明剧本强度不足。
2. **max_compression_ratio**：反映极端情况下压缩层的上限。
3. **total_chars / est_tokens**：三组对照；理想结果是 A_on 的 est_tokens显著低于 B_off，且与 C_truncate 接近或更低，同时任务完成判定通过。

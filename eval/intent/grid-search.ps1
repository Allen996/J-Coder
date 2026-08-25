# Intent L1 hyperparameter grid search.
#
# 方案 B 版本:在历史 4×3×3 = 36 组 (wLlm × penConflict × penNegative) 之上,
# 增加 calibration.enabled ∈ {true, false} 对照,共 36 + 36 = 72 组(每组跑 train split 一次)。
#
# 注意:
#   1) 本脚本只能跑 TRAIN。dev/test 不准在这里跑。
#   2) 跑完选最优超参 → 跑一次 dev 验证 → 再跑一次 test 报最终数字。
#   3) slot 已退出加权(方案 B 调整),所以 wSlot 在 scorer 内部不再参与 final_conf。
#      脚本仍把 wSlot 注入到 properties(供日志可观测),但实际不影响分数。
#
# 用法: pwsh -File eval/intent/grid-search.ps1
# 默认 calibration 阈值来自 application.yml;可在命令行通过 -Dintent.eval.cal.* 覆盖(已支持)。

$ErrorActionPreference = "Stop"

$wLlmVals     = @(0.40, 0.50, 0.60, 0.70)
$penConfs     = @(0.10, 0.15, 0.20)
$penNegs      = @(0.05, 0.10, 0.15)
$calibFlags   = @($true, $false)

$results = New-Object "System.Collections.Generic.List[object]"
$count = 0
$total = $wLlmVals.Count * $penConfs.Count * $penNegs.Count * $calibFlags.Count
Write-Host "Total combinations: $total"

foreach ($calibOn in $calibFlags) {
    foreach ($wLlm in $wLlmVals) {
        $wKw = [Math]::Round((1 - $wLlm) / 2, 4)
        $wSlot = [Math]::Round((1 - $wLlm) / 2, 4)
        foreach ($penC in $penConfs) {
            foreach ($penN in $penNegs) {
                $count++
                $calibLabel = if ($calibOn) { "ON" } else { "OFF" }
                Write-Host "[$count/$total] calib=$calibLabel wLlm=$wLlm wKw=$wKw wSlot=$wSlot penC=$penC penN=$penN"
                $output = mvn test -q "-Dtest=IntentGoldenSetEvalTest" `
                    "-Dintent.eval.split=train" `
                    "-Dintent.eval.wLlm=$wLlm" `
                    "-Dintent.eval.wKeyword=$wKw" `
                    "-Dintent.eval.wSlot=$wSlot" `
                    "-Dintent.eval.penConflict=$penC" `
                    "-Dintent.eval.penNegative=$penN" `
                    "-Dintent.eval.calibration.enabled=$($calibOn.ToString().ToLower())" 2>&1
                # 找到最新的 summary 文件
                $latest = Get-ChildItem "eval/intent/results/intent-summary-train-*.json" `
                    | Sort-Object LastWriteTime -Descending | Select-Object -First 1
                if (-not $latest) {
                    Write-Host "  [WARN] no summary file produced"
                    continue
                }
                $summary = Get-Content $latest.FullName -Encoding UTF8 | ConvertFrom-Json
                $obj = [PSCustomObject]@{
                    calibration = $calibOn
                    wLlm        = $wLlm
                    wKeyword    = $wKw
                    wSlot       = $wSlot
                    penConflict = $penC
                    penNegative = $penN
                    top1        = $summary.top1_accuracy
                    macro_f1    = $summary.macro_f1
                    fallback    = $summary.fallback_rate
                    tier_direct = $summary.tier_direct
                    tier_offer  = $summary.tier_offer
                    tier_clarify= $summary.tier_clarify
                    ot_recall   = if ($summary.off_topic_subset) { $summary.off_topic_subset.recall } else { 0.0 }
                    ot_top1     = if ($summary.off_topic_subset) { $summary.off_topic_subset.top1 } else { 0.0 }
                    file        = $latest.Name
                }
                $results.Add($obj)
                Write-Host ("  -> top1={0:N4} macro_f1={1:N4} ot_recall={2:N4} file={3}" -f $obj.top1, $obj.macro_f1, $obj.ot_recall, $obj.file)
            }
        }
    }
}

# Write leaderboard
$outPath = "eval/intent/results/grid-search-leaderboard.json"
$results | Sort-Object top1 -Descending | ConvertTo-Json -Depth 5 | Set-Content -Path $outPath -Encoding UTF8

Write-Host "`n=========== GRID SEARCH LEADERBOARD ==========="
$results | Sort-Object top1 -Descending | Format-Table -AutoSize calibration, wLlm, wKeyword, wSlot, penConflict, penNegative, top1, macro_f1, ot_recall
Write-Host "`nWrote: $outPath"
$ErrorActionPreference = "Stop"
$jsonl = Get-Content -Path "eval/intent/golden.jsonl" -Encoding UTF8 -Raw

$rows = @()
foreach ($line in ($jsonl -split "`n")) {
  $line = $line.Trim()
  if ([string]::IsNullOrEmpty($line)) { continue }
  $o = $line | ConvertFrom-Json
  $rows += [PSCustomObject]@{
    id = $o.id
    gold_label = $o.gold_label
    difficulty = $o.difficulty
    language = $o.language
  }
}

# Goal: 60 test, 40 dev, 200 train. Balanced by gold_label.
$byLabel = $rows | Group-Object gold_label
$labelOrder = @("READ_CODE", "WRITE_PROJECT", "RUN_COMMAND", "CHAT_QA", "PLANNING")

# Per-label target test ratio (~0.2). Round to nearest bucket-friendly number.
$testBudget = 60
$devBudget = 40

$test = New-Object "System.Collections.Generic.List[string]"
$dev = New-Object "System.Collections.Generic.List[string]"
$train = New-Object "System.Collections.Generic.List[string]"

foreach ($label in $labelOrder) {
  $bucket = @($byLabel | Where-Object { $_.Name -eq $label } | ForEach-Object { $_.Group } | Sort-Object id)
  $n = $bucket.Count
  $nTest = [int][Math]::Round($n * 0.2)
  if ($nTest -lt 8) { $nTest = 8 }
  $nDev = [int][Math]::Round($n * 0.13)
  if ($nDev -lt 5) { $nDev = 5 }
  $nTrain = $n - $nTest - $nDev
  if ($nTrain -lt 0) { $nTrain = 0; $nDev = $n - $nTest }
  Write-Host ("$label  n=$n  test=$nTest  dev=$nDev  train=$nTrain")
  # Take first nTest for test, next nDev for dev, rest for train.
  for ($i = 0; $i -lt $nTest; $i++) { $test.Add($bucket[$i].id) }
  for ($i = $nTest; $i -lt ($nTest + $nDev); $i++) { $dev.Add($bucket[$i].id) }
  for ($i = ($nTest + $nDev); $i -lt $n; $i++) { $train.Add($bucket[$i].id) }
}

# Adjust to hit exact 60/40 if needed (sum of per-bucket may differ).
$testExcess = $test.Count - $testBudget
$devExcess = $dev.Count - $devBudget
Write-Host "`nRaw counts: test=$($test.Count) dev=$($dev.Count) train=$($train.Count)"

# Move extras from largest buckets to compensate (only if needed).
# For simplicity, just take the first N of test, last N of train if test < 60.
while ($test.Count -lt $testBudget -and $train.Count -gt 0) {
  $test.Add($train[0]); $train.RemoveAt(0)
}
while ($dev.Count -lt $devBudget -and $train.Count -gt 0) {
  $dev.Add($train[0]); $train.RemoveAt(0)
}

Write-Host ("Final: test={0} dev={1} train={2}" -f $test.Count, $dev.Count, $train.Count)

# Verify disjoint
$hset = New-Object "System.Collections.Generic.HashSet[string]"
$overlap = @()
foreach ($x in $test) { if ($hset.Contains($x)) { $overlap += $x } ; $null = $hset.Add($x) }
foreach ($x in $dev)  { if ($hset.Contains($x)) { $overlap += $x } ; $null = $hset.Add($x) }
foreach ($x in $train){ if ($hset.Contains($x)) { $overlap += $x } ; $null = $hset.Add($x) }
Write-Host ("overlap=" + $overlap.Count)
Write-Host ("union=" + $hset.Count)

$ids = $rows | ForEach-Object { $_.id }
$missing = @()
foreach ($i in $ids) { if (-not $hset.Contains($i)) { $missing += $i } }
Write-Host ("missing=" + $missing.Count)

# Build JSON manually to avoid PowerShell encoding issues with non-ASCII.
$testArr = ($test | ForEach-Object { "`"$_`"" }) -join ","
$devArr = ($dev | ForEach-Object { "`"$_`"" }) -join ","
$trainArr = ($train | ForEach-Object { "`"$_`"" }) -join ","
$doc = "OFFICIAL SPLIT: test=60, dev=40, train=200 (total 300). Balanced per gold_label. DO NOT re-shuffle the whole file and re-split downstream; this partition is canonical. All tuning (KeywordSignalExtractor.KEYWORDS, LocalIntentScorer weights/penalties, IntentPrompter thresholds, ChatModelLlmIntentClassifier prompt) must be done on train, with dev used for hyperparameter selection. test may be evaluated only once for the final reported number."
$json = "{`"_doc`":`"$doc`",`"counts`":{`"train`":$($train.Count),`"dev`":$($dev.Count),`"test`":$($test.Count)},`"splits`":{`"test`":[$testArr],`"dev`":[$devArr],`"train`":[$trainArr]}}"
[System.IO.File]::WriteAllText("eval/intent/golden.splits.json", $json, [System.Text.UTF8Encoding]::new($false))
Write-Host "`nWrote eval/intent/golden.splits.json"
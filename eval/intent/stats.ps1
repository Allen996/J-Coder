$ErrorActionPreference = "Stop"
$jsonl = Get-Content -Path "eval\intent\golden.jsonl" -Encoding UTF8 -Raw

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
    source = $o.source
    multi_intent = $o.multi_intent
    gold_negative = $o.gold_negative
  }
}

Write-Host "===== TOTAL ====="
Write-Host ("rows: " + $rows.Count)

Write-Host "`n===== by gold_label ====="
$rows | Group-Object gold_label | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }

Write-Host "`n===== by difficulty ====="
$rows | Group-Object difficulty | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }

Write-Host "`n===== by language ====="
$rows | Group-Object language | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }

Write-Host "`n===== by source ====="
$rows | Group-Object source | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }

Write-Host "`n===== label x difficulty ====="
$rows | Group-Object gold_label, difficulty | ForEach-Object {
  $parts = $_.Name -split ", "
  "{0,-15} {1,-10} {2,5}" -f $parts[0], $parts[1], $_.Count
} | Sort-Object

Write-Host "`n===== label x language ====="
$rows | Group-Object gold_label, language | ForEach-Object {
  $parts = $_.Name -split ", "
  "{0,-15} {1,-10} {2,5}" -f $parts[0], $parts[1], $_.Count
} | Sort-Object

Write-Host "`n===== gold_negative count ====="
$rows | Group-Object gold_negative | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }

Write-Host "`n===== multi_intent count ====="
$rows | Group-Object multi_intent | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }

# Verify splits file
Write-Host "`n===== splits file check ====="
$splits = Get-Content "eval\intent\golden.splits.json" -Encoding UTF8 -Raw | ConvertFrom-Json
$ids = $rows | ForEach-Object { $_.id }
$idset = New-Object "System.Collections.Generic.HashSet[string]"
$ids | ForEach-Object { $null = $idset.Add($_) }

$test = $splits.splits.test
$dev = $splits.splits.dev
$train = $splits.splits.train

$testMissing = @()
$devMissing = @()
$trainMissing = @()
foreach ($t in $test) { if (-not $idset.Contains($t)) { $testMissing += $t } }
foreach ($d in $dev) { if (-not $idset.Contains($d)) { $devMissing += $d } }
foreach ($tr in $train) { if (-not $idset.Contains($tr)) { $trainMissing += $tr } }
Write-Host ("test: declared=" + $test.Count + " present=" + ($test.Count - $testMissing.Count) + " missing=" + $testMissing.Count)
Write-Host ("dev:  declared=" + $dev.Count + " present=" + ($dev.Count - $devMissing.Count) + " missing=" + $devMissing.Count)
Write-Host ("train: declared=" + $train.Count + " present=" + ($train.Count - $trainMissing.Count) + " missing=" + $trainMissing.Count)
Write-Host ("counts.train=" + $splits.counts.train + " counts.dev=" + $splits.counts.dev + " counts.test=" + $splits.counts.test)
Write-Host ("split_id_set_size=" + ($test.Count + $dev.Count + $train.Count))

# Check no overlap
$allSplitIds = New-Object "System.Collections.Generic.HashSet[string]"
$overlap = @()
foreach ($t in $test) { if ($allSplitIds.Contains($t)) { $overlap += $t } ; $null = $allSplitIds.Add($t) }
foreach ($d in $dev) { if ($allSplitIds.Contains($d)) { $overlap += $d } ; $null = $allSplitIds.Add($d) }
foreach ($tr in $train) { if ($allSplitIds.Contains($tr)) { $overlap += $tr } ; $null = $allSplitIds.Add($tr) }
Write-Host ("overlap_count=" + $overlap.Count)

# IDs not in any split
$notInAnySplit = @()
foreach ($id in $ids) { if (-not $allSplitIds.Contains($id)) { $notInAnySplit += $id } }
Write-Host ("ids_not_in_any_split=" + $notInAnySplit.Count)
if ($notInAnySplit.Count -gt 0 -and $notInAnySplit.Count -lt 20) {
  Write-Host "  missing IDs:"
  $notInAnySplit | ForEach-Object { Write-Host "  $_" }
}
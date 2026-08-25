$ErrorActionPreference = "Stop"
$jsonl = Get-Content -Path "eval/intent/l2-decisions.jsonl" -Encoding UTF8 -Raw

$count = 0
$err = 0
$rows = @()
foreach ($line in ($jsonl -split "`n")) {
  $line = $line.Trim()
  if ([string]::IsNullOrEmpty($line)) { continue }
  $count++
  try {
    $o = $line | ConvertFrom-Json -ErrorAction Stop
    $rows += [PSCustomObject]@{
      id = $o.id
      l1_label = $o.l1_label
      tool = $o.tool
      gold_decision = $o.gold_decision
      is_smoke = $o.is_smoke
    }
  } catch {
    $err++
    Write-Host "BAD line $count"
  }
}

Write-Host "Total: $count, Errors: $err"
Write-Host ""
Write-Host "===== by gold_decision ====="
$rows | Group-Object gold_decision | ForEach-Object { "{0,-10} {1,5}" -f $_.Name, $_.Count }
Write-Host ""
Write-Host "===== by l1_label ====="
$rows | Group-Object l1_label | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }
Write-Host ""
Write-Host "===== by l1_label x gold_decision ====="
$rows | Group-Object l1_label, gold_decision | ForEach-Object {
  $parts = $_.Name -split ", "
  "{0,-15} {1,-10} {2,5}" -f $parts[0], $parts[1], $_.Count
} | Sort-Object
Write-Host ""
Write-Host "===== is_smoke count ====="
$rows | Group-Object is_smoke | ForEach-Object { "{0,-15} {1,5}" -f $_.Name, $_.Count }
Write-Host ""
Write-Host "===== smoke count by gold_decision ====="
$rows | Where-Object { $_.is_smoke -eq $true } | Group-Object gold_decision | ForEach-Object { "{0,-10} {1,5}" -f $_.Name, $_.Count }
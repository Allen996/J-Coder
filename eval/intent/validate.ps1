$ErrorActionPreference = "Stop"
$jsonl = Get-Content -Path "eval\intent\golden.jsonl" -Encoding UTF8 -Raw
$count = 0
$err = 0
$bad = New-Object System.Collections.Generic.List[string]
foreach ($line in ($jsonl -split "`n")) {
  $line = $line.Trim()
  if ([string]::IsNullOrEmpty($line)) { continue }
  $count++
  try {
    $null = $line | ConvertFrom-Json -ErrorAction Stop
  } catch {
    $err++
    if ($bad.Count -lt 20) { $bad.Add("L$count : " + $line.Substring(0, [Math]::Min(60, $line.Length)) + "...") }
  }
}
Write-Host "Total: $count, Errors: $err"
$bad | ForEach-Object { Write-Host $_ }
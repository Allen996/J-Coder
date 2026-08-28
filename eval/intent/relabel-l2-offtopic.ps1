$ErrorActionPreference = "Stop"

$inPath = "eval/intent/l2-decisions.jsonl"
$outPath = "eval/intent/l2-decisions.jsonl.new"

$content = [System.IO.File]::ReadAllText($inPath, [System.Text.UTF8Encoding]::new($false))
$lines = $content -split "`n"
$out = New-Object "System.Collections.Generic.List[string]"
$changed = 0

foreach ($line in $lines) {
    $trim = $line.Trim()
    if ([string]::IsNullOrEmpty($trim)) { $out.Add($line); continue }
    if ($trim -match '"l1_label":"OFF_TOPIC"') {
        $new = $trim -replace '"l1_label":"OFF_TOPIC"', '"l1_label":"CHAT_QA"'
        $new = $new -replace '"off"', '"chat"'
        $out.Add($new); $changed++
    } else { $out.Add($line) }
}

[System.IO.File]::WriteAllText($outPath, ($out -join "`n"), [System.Text.UTF8Encoding]::new($false))
Write-Host "changed=$changed"
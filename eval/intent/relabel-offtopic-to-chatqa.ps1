$ErrorActionPreference = "Stop"

$inPath = "eval/intent/golden.jsonl"
$outPath = "eval/intent/golden.jsonl.new"

$content = [System.IO.File]::ReadAllText($inPath, [System.Text.UTF8Encoding]::new($false))
$lines = $content -split "`n"
$out = New-Object "System.Collections.Generic.List[string]"
$changed = 0

foreach ($line in $lines) {
    $trim = $line.Trim()
    if ([string]::IsNullOrEmpty($trim)) {
        $out.Add($line)
        continue
    }

    # 把 gold_label=OFF_TOPIC 改成 CHAT_QA
    if ($trim -match '"gold_label":"OFF_TOPIC"') {
        $new = $trim -replace '"gold_label":"OFF_TOPIC"', '"gold_label":"CHAT_QA"'
        # tags 里的 off-topic 也改成 chat
        $new = $new -replace '"off-topic"', '"chat"'
        $out.Add($new)
        $changed++
    } else {
        $out.Add($line)
    }
}

[System.IO.File]::WriteAllText($outPath, ($out -join "`n"), [System.Text.UTF8Encoding]::new($false))
Write-Host "changed=$changed"
Write-Host "wrote $outPath"
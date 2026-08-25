$key = "sk-ea7271e381764060a834b759291252a4"
$base = "https://token-plan.cn-beijing.maas.aliyuncs.com"

$models = @("qwen-turbo", "qwen-plus", "qwen-max", "qwen-long", "qwen3.7-flash", "qwen3.7-plus", "qwen3-flash", "qwen3-plus")

foreach ($m in $models) {
    Write-Host "`n=== Model: $m ==="
    $body = @{
        model = $m
        messages = @( @{ role = "user"; content = "hi" } )
        temperature = 0
        max_tokens = 8
    } | ConvertTo-Json -Depth 5 -Compress

    try {
        $resp = Invoke-WebRequest -Uri "$base/compatible-mode/v1/chat/completions" `
            -Method Post `
            -Headers @{ "Authorization" = "Bearer $key"; "Content-Type" = "application/json" } `
            -Body $body `
            -TimeoutSec 30 `
            -UseBasicParsing `
            -ErrorAction Stop
        $body_short = $resp.Content.Substring(0, [Math]::Min(400, $resp.Content.Length))
        Write-Host "HTTP $($resp.StatusCode): $body_short"
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            try {
                $stream = $resp.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $err = $reader.ReadToEnd()
                Write-Host "HTTP $($resp.StatusCode): $err"
            } catch { Write-Host "ERR: $($_.Exception.Message)" }
        } else {
            Write-Host "ERR: $($_.Exception.Message)"
        }
    }
}
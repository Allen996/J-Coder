$key = "sk-ea7271e381764060a834b759291252a4"

Write-Host "Key length: $($key.Length)"

# 测试1: 原生 DashScope endpoint
$body1 = @'
{"model":"qwen-turbo","input":{"messages":[{"role":"user","content":"hi"}]},"parameters":{"temperature":0}}
'@

Write-Host "`n=== Test 1: native DashScope + qwen-turbo ==="
try {
    $resp = Invoke-WebRequest -Uri "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation" `
        -Method Post `
        -Headers @{ "Authorization" = "Bearer $key"; "Content-Type" = "application/json" } `
        -Body $body1 `
        -TimeoutSec 30 `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "HTTP Status: $($resp.StatusCode)"
    Write-Host "Body: $($resp.Content.Substring(0, [Math]::Min(800, $resp.Content.Length)))"
} catch {
    Write-Host "Caught: $($_.Exception.Message)"
    $resp = $_.Exception.Response
    if ($resp) {
        Write-Host "HTTP Status: $($resp.StatusCode)"
        try {
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $body = $reader.ReadToEnd()
            Write-Host "Body: $body"
        } catch { Write-Host "Could not read body: $($_.Exception.Message)" }
    }
}

# 测试2: OpenAI 兼容
$body2 = @'
{"model":"qwen-turbo","messages":[{"role":"user","content":"hi"}],"temperature":0}
'@

Write-Host "`n=== Test 2: OpenAI-compatible + qwen-turbo ==="
try {
    $resp = Invoke-WebRequest -Uri "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" `
        -Method Post `
        -Headers @{ "Authorization" = "Bearer $key"; "Content-Type" = "application/json" } `
        -Body $body2 `
        -TimeoutSec 30 `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "HTTP Status: $($resp.StatusCode)"
    Write-Host "Body: $($resp.Content.Substring(0, [Math]::Min(800, $resp.Content.Length)))"
} catch {
    Write-Host "Caught: $($_.Exception.Message)"
    $resp = $_.Exception.Response
    if ($resp) {
        Write-Host "HTTP Status: $($resp.StatusCode)"
        try {
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            $body = $reader.ReadToEnd()
            Write-Host "Body: $body"
        } catch { Write-Host "Could not read body: $($_.Exception.Message)" }
    }
}
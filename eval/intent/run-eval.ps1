# Eval skeleton for the Intent Golden Set.
# Skeleton only: real implementation needs to wire Spring's ApplicationContext so
# that ChatModelLlmIntentClassifier, KeywordSignalExtractor, SlotCompletenessChecker,
# LocalIntentScorer, IntentGate, LlmToolGate are all available. The actual full
# implementation belongs in src/test/java/org/example/agent/intent/eval/ (next PR).
#
# This file is intentionally minimal so the dataset itself can be reviewed first.

param(
    [string]$Split = "test",        # train | dev | test
    [string]$Golden = "eval/intent/golden.jsonl",
    [string]$Splits = "eval/intent/golden.splits.json",
    [string]$L2 = "eval/intent/l2-decisions.jsonl",
    [string]$OutDir = "eval/intent/results"
)

$ErrorActionPreference = "Stop"

# Load splits
$splitObj = Get-Content $Splits -Encoding UTF8 -Raw | ConvertFrom-Json
$idSet = New-Object "System.Collections.Generic.HashSet[string]"
foreach ($id in $splitObj.splits.$Split) { $null = $idSet.Add($id) }
Write-Host ("Split: $Split  ids={0}" -f $idSet.Count)

# Iterate golden.jsonl, pick rows whose id is in the split
$rows = @()
foreach ($line in (Get-Content $Golden -Encoding UTF8 -Raw) -split "`n") {
  $line = $line.Trim()
  if ([string]::IsNullOrEmpty($line)) { continue }
  $o = $line | ConvertFrom-Json
  if ($idSet.Contains($o.id)) {
    $rows += $o
  }
}
Write-Host ("Loaded {0} rows from golden.jsonl" -f $rows.Count)

# Iterate l2-decisions.jsonl (no split; whole file used as smoke)
$l2rows = @()
foreach ($line in (Get-Content $L2 -Encoding UTF8 -Raw) -split "`n") {
  $line = $line.Trim()
  if ([string]::IsNullOrEmpty($line)) { continue }
  $l2rows += ($line | ConvertFrom-Json)
}
Write-Host ("Loaded {0} rows from l2-decisions.jsonl" -f $l2rows.Count)

# ----------------------------------------------------------------------
# Stub L1 evaluation:
# Replace this block with a real call into the Spring ApplicationContext:
#     ctx = new SpringApplicationBuilder().sources(Main.class).run();
#     gate = ctx.getBean(IntentGate.class);
#     gate.classify(executionId, row.input)
# and compare gate.primaryLabel() to row.gold_label.
# For now, just emit a placeholder per-row report.
# ----------------------------------------------------------------------
$report = New-Object "System.Collections.Generic.List[object]"
foreach ($row in $rows) {
  $report.Add([PSCustomObject]@{
    id          = $row.id
    input       = $row.input
    gold_label  = $row.gold_label
    pred_label  = "PENDING_REAL_RUN"
    pred_conf   = 0.0
    tier        = "PENDING"
    fallback    = $false
    fb_reason   = ""
    duration_ms = 0
  })
}

# ----------------------------------------------------------------------
# Stub L2 evaluation: same — wire LlmToolGate.evaluate(ctx, tool, argsJson, step)
# and compare to gold_decision. Emit per-row report.
# ----------------------------------------------------------------------
$l2report = New-Object "System.Collections.Generic.List[object]"
foreach ($r in $l2rows) {
  $l2report.Add([PSCustomObject]@{
    id            = $r.id
    l1_label      = $r.l1_label
    tool          = $r.tool
    gold_decision = $r.gold_decision
    pred_decision = "PENDING_REAL_RUN"
    pred_conf     = 0.0
    is_smoke      = $r.is_smoke
  })
}

# Placeholder metrics (all zeros until real run wires up).
$placeholder = [PSCustomObject]@{
  generated_at   = (Get-Date).ToString("o")
  split          = $Split
  n_rows         = $rows.Count
  n_l2_rows      = $l2rows.Count
  top1_accuracy  = 0.0
  macro_f1       = 0.0
  per_class      = @{}
  confusion      = @{}
  fallback_rate  = 0.0
  tier_distribution = @{}
  l2_over_block_rate = 0.0
  l2_smoke_pass_rate = 0.0
  notes          = "SKELETON: wire to Spring ApplicationContext to get real metrics."
}
if (-not (Test-Path $OutDir)) {
  New-Item -ItemType Directory -Path $OutDir | Out-Null
}
$outPath = Join-Path $OutDir ("intent-{0}-{1}.json" -f $Split, (Get-Date -Format "yyyyMMdd-HHmmss"))
$placeholder | ConvertTo-Json -Depth 6 | Set-Content -Path $outPath -Encoding UTF8
Write-Host ("Wrote skeleton report: $outPath")
Write-Host "TODO: real implementation in next PR (Spring app context + ChatModel + LlmToolGate)."
# =============================================================================
# D40 R03-09 D37 冻结回归脚本（三重纪律）
# 1) PRE：核验冻结基线 SHA-256 与任务书一致；
# 2) 全量 javac 重编评测器（不依赖 run-evaluation.ps1 的增量判定，避免内部类缺失）；
# 3) IN_MEMORY_LOCAL 重跑（Rounds=3 Warmup=3 TopK=5 Threshold=0.0，与基线同参），
#    输出隔离到 R03/regression/d37-rerun，不触碰 D37 冻结目录；
# 4) 剔除 4 个耗时列后逐行逐列比较质量列，diff 必须为 0。
# 退出码：0=PASS；1=FAIL/BLOCKED。
# =============================================================================
param(
    [string]$JavaExe = "D:\SoftwareInstall\JDK\JDK21\bin\java.exe",
    [string]$JavacExe = "D:\SoftwareInstall\JDK\JDK21\bin\javac.exe",
    [string]$RepoRoot = "D:\SoftwareInstall\Apache\repository",
    [string]$EvidenceRoot = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence",
    [string]$OutDir = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D40\R03\regression"
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$frozen = Join-Path $EvidenceRoot "D37\baseline\v1.0\IN_MEMORY_LOCAL\20261120-195125\baseline.csv"
$expectedSha = "CD875F83611F356537790CE856797DA8057A5DCF735D82B63508A80CE1369C3F"
$preSha = (Get-FileHash $frozen -Algorithm SHA256).Hash
if ($preSha -ne $expectedSha) {
    "BLOCKED: frozen baseline SHA mismatch: $preSha" | Out-File -Encoding utf8 (Join-Path $OutDir "D37-quality-columns-diff.txt")
    exit 1
}

$jackson = @(
    "$RepoRoot\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar",
    "$RepoRoot\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar",
    "$RepoRoot\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
) -join ";"
$srcDir = Join-Path $EvidenceRoot "D37\evaluator\src"
$outClasses = Join-Path $EvidenceRoot "D37\evaluator\out"
$sources = Get-ChildItem $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& $JavacExe -encoding UTF-8 -cp $jackson -d $outClasses @sources
if ($LASTEXITCODE -ne 0) { "BLOCKED: evaluator javac failed" | Out-File -Encoding utf8 (Join-Path $OutDir "D37-quality-columns-diff.txt"); exit 1 }

$rerunBase = Join-Path $OutDir "d37-rerun"
& $JavaExe '-Dstdout.encoding=UTF-8' -cp "$outClasses;$jackson" com.zl.d37.evaluator.Main `
    -Backend IN_MEMORY_LOCAL -Rounds 3 -Warmup 3 -TopK 5 -Threshold 0.0 -OutputBase $rerunBase `
    2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir "d37-rerun.log")
if ($LASTEXITCODE -ne 0) { "BLOCKED: evaluator run exit=$LASTEXITCODE" | Out-File -Encoding utf8 (Join-Path $OutDir "D37-quality-columns-diff.txt"); exit 1 }

$newRunDir = Get-ChildItem (Join-Path $rerunBase "baseline\v1.0\IN_MEMORY_LOCAL") -Directory | Sort-Object Name | Select-Object -Last 1
$newCsv = Join-Path $newRunDir.FullName "baseline.csv"
$newSha = (Get-FileHash $newCsv -Algorithm SHA256).Hash

$exclude = @("queryEmbeddingMs", "retrievalElapsedMillis", "totalElapsedMillis", "inMemorySearchMs")
$pre = Import-Csv $frozen
$post = Import-Csv $newCsv
$diffs = [System.Collections.Generic.List[string]]::new()
if ($pre.Count -ne $post.Count) { $diffs.Add("row count: pre=$($pre.Count) post=$($post.Count)") }
$cols = $pre[0].PSObject.Properties.Name | Where-Object { $exclude -notcontains $_ }
for ($i = 0; $i -lt [Math]::Min($pre.Count, $post.Count); $i++) {
    foreach ($c in $cols) {
        if ($pre[$i].$c -ne $post[$i].$c) {
            $diffs.Add("row=$($i+1) question=$($pre[$i].questionId) round=$($pre[$i].round) col=$c pre=$($pre[$i].$c) post=$($post[$i].$c)")
        }
    }
}
$verdict = if ($diffs.Count -eq 0 -and $pre.Count -eq $post.Count) { "PASS" } else { "FAIL" }
$report = @(
    "R03-09 D37 IN_MEMORY_LOCAL 重跑 vs 冻结基线（剔除耗时列 queryEmbeddingMs/retrievalElapsedMillis/totalElapsedMillis/inMemorySearchMs）",
    "quality-columns diff = $($diffs.Count)",
    "rows compared = $($pre.Count)",
    "quality columns compared = $($cols.Count)",
    "pre-frozen-sha = $preSha",
    "new-run-sha = $newSha",
    "new-run-dir = $($newRunDir.FullName)",
    "verdict = $verdict"
) + $diffs
$report -join "`n" | Out-File -Encoding utf8 (Join-Path $OutDir "D37-quality-columns-diff.txt")
Write-Host ($report -join "`n")
if ($verdict -ne "PASS") { exit 1 }
exit 0

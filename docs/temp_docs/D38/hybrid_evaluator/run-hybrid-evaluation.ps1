# run-hybrid-evaluation.ps1 — D38 R02 hybrideval 编译+自测+评测 一键脚本（仿 D37 版）
# 退出码：0 成功；1 编译/自测/参数失败；2 环境不可用；3 跨 run 投影一致性熔断
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$DatasetDir = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D37\dataset",
    [string]$CorpusPath = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D37\dataset\knowledge-corpus.v1.0.json",
    [string]$OutputBase = "",
    [int]$Runs = 2,
    [int]$Rounds = 3,
    [int]$Warmup = 3,
    [int]$TopK = 5,
    [switch]$SelfTestOnly
)

$ErrorActionPreference = "Stop"
$root = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs\D38\hybrid_evaluator"
$srcDir = Join-Path $root "src"
$outDir = Join-Path $root "out"
$repo = "D:\SoftwareInstall\Apache\repository"
$jackson = @(
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar"),
    (Join-Path $repo "com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar")
) -join ";"
$javaHome = "D:\SoftwareInstall\JDK\JDK21"
$javac = Join-Path $javaHome "bin\javac.exe"
$java = Join-Path $javaHome "bin\java.exe"

if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$sources = Get-ChildItem -Path $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$argFile = Join-Path $root "javac-args.txt"
@(("-encoding UTF-8"), ("-cp `"" + ($jackson -replace '\\','/') + "`""), ("-d `"" + ($outDir -replace '\\','/') + "`""),
   ($sources | ForEach-Object { '"' + ($_ -replace '\\','/') + '"' })) -join "`n" |
    Set-Content -LiteralPath $argFile -Encoding utf8

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE_FAILED"; exit 1 }
Write-Host "COMPILE_OK ($($sources.Count) files)"

if ($SelfTestOnly -eq $false) {
    if ([string]::IsNullOrWhiteSpace($OutputBase)) { Write-Host "缺少 -OutputBase"; exit 1 }
}
$stOut = Join-Path $root ("metrics-selftest-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".txt")
& $java -cp "$outDir;$jackson" com.zl.d38.hybrideval.Main -mode selftest -outputBase $root
if ($LASTEXITCODE -ne 0) { Write-Host "SELFTEST_FAILED"; exit 1 }
Write-Host "SELFTEST_OK"
if ($SelfTestOnly) { exit 0 }

& $java "-Dfile.encoding=UTF-8" -cp "$outDir;$jackson" com.zl.d38.hybrideval.Main `
    -baseUrl $BaseUrl -datasetDir $DatasetDir -corpusPath $CorpusPath `
    -outputBase $OutputBase -runs $Runs -rounds $Rounds -warmup $Warmup -topK $TopK
$code = $LASTEXITCODE
Write-Host "EVAL_EXIT=$code"
exit $code

# =============================================================================
# D37 RAG 评测器一条命令入口：编译 + 运行（Windows PowerShell 7）
# 用法示例：
#   & run-evaluation.ps1 -BaseUrl "http://127.0.0.1:8080" -DatasetVersion "v1.0" -Rounds 3 -Warmup 3
#   & run-evaluation.ps1 -Backend IN_MEMORY_LOCAL -Rounds 3 -Warmup 3   # 内存降级后端
#   & run-evaluation.ps1 -SelftestOnly                                   # 仅跑自测
# 退出码：全 PASS=0；有 FAIL=1；仅 BLOCKED/NOT RUN=2
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$DatasetVersion = "v1.0",
    [int]$Rounds = 3,
    [int]$Warmup = 3,
    [string]$Backend = "AUTO",
    [double]$Threshold = 0.0,
    [int]$TopK = 5,
    [switch]$SelftestOnly
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$srcDir = Join-Path $scriptDir "evaluator\src"
$outDir = Join-Path $scriptDir "evaluator\out"
$java = "D:\SoftwareInstall\JDK\JDK21\bin\java.exe"
$javac = "D:\SoftwareInstall\JDK\JDK21\bin\javac.exe"
$repo = "D:\SoftwareInstall\Apache\repository"

$jackson = @(
    "$repo\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar",
    "$repo\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar",
    "$repo\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
)
foreach ($j in $jackson) {
    if (-not (Test-Path $j)) {
        Write-Error "缺少 Jackson jar: $j"
        exit 1
    }
}
$classpath = ($jackson -join ";")

# 编译（增量：源文件比 class 新才重编）
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$sources = Get-ChildItem $srcDir -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$latestSrc = ($sources | ForEach-Object { (Get-Item $_).LastWriteTime } | Measure-Object -Maximum).Maximum
$classesExist = Test-Path (Join-Path $outDir "com\zl\d37\evaluator\Main.class")
$latestClass = if ($classesExist) {
    (Get-ChildItem $outDir -Recurse -Filter *.class | ForEach-Object { $_.LastWriteTime } | Measure-Object -Maximum).Maximum
} else { [DateTime]::MinValue }

if (-not $classesExist -or $latestSrc -gt $latestClass) {
    Write-Host "编译评测器源码..."
    & $javac -encoding UTF-8 -cp $classpath -d $outDir @sources
    if ($LASTEXITCODE -ne 0) {
        Write-Error "编译失败，退出码 $LASTEXITCODE"
        exit 1
    }
}

$runCp = "$outDir;$classpath"
if ($SelftestOnly) {
    & $java -cp $runCp com.zl.d37.evaluator.Main -SelftestOnly
    exit $LASTEXITCODE
}

$args2 = @(
    "-BaseUrl", $BaseUrl,
    "-DatasetVersion", $DatasetVersion,
    "-Rounds", $Rounds,
    "-Warmup", $Warmup,
    "-Backend", $Backend,
    "-Threshold", $Threshold,
    "-TopK", $TopK
)
& $java -cp $runCp com.zl.d37.evaluator.Main @args2
exit $LASTEXITCODE

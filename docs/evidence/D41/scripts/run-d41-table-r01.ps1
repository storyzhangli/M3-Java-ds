# =============================================================================
# D41 R01 真实演练脚本：夹具入库 + A1–A5 异常矩阵 + 源表逐格对账 + C08 双轨
#                    + C09 权限审计 + 重抽幂等 + 删除闭环绑定 + TABLE_ORPHAN + 清理
#
# 用法（密钥仅环境变量注入，禁止硬编码/落盘/提交）：
#   $env:MYSQL_PASSWORD=...; $env:PG_VECTOR_PASSWORD=...
#   pwsh -File run-d41-table-r01.ps1
# 退出码：0=全部 PASS；1=存在 FAIL。
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:9090",
    [string]$OutDir = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D41\r01-exercise"
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$repo = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds"
$module = Join-Path $repo "D41\D41_TASIIEM"
$fixDir = Join-Path $repo "docs\temp_docs\D41\fixtures"
$scripts = Join-Path $repo "docs\evidence\D41\scripts"
$javaExe = "D:\SoftwareInstall\JDK\JDK21\bin\java.exe"
$mvn = "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd"
$mysqlJar = "D:\SoftwareInstall\Apache\repository\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar"
$pgJar = "D:\SoftwareInstall\Apache\repository\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar"
$dbexec = Join-Path $repo "docs\evidence\D40\R03\scripts\DbExec.java"
$batch = "d41t-r01-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$script:DocIds = [System.Collections.Generic.List[string]]::new()
$script:Results = [ordered]@{}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Fail([string]$m) { throw $m }
function Invoke-Db {
    param([ValidateSet("mysql","pg")][string]$Target, [string]$Sql)
    $jar = if ($Target -eq "mysql") { $mysqlJar } else { $pgJar }
    $out = & $javaExe '-Dstdout.encoding=UTF-8' -cp $jar $dbexec $Target -e $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "DB exec failed ($Target): $out" }
    return ($out -join "`n")
}
function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [string]$FormFile, [string]$Token)
    # 显式传 -Token '' 视为无凭据请求；未传 Token 用 ADMIN token
    $tok = if ($PSBoundParameters.ContainsKey('Token')) { $Token } else { $script:AdminToken }
    $headers = @{}
    if ($tok) { $headers["Authorization"] = "Bearer $tok" }
    $uri = "$BaseUrl$Path"
    try {
        if ($FormFile) {
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Form @{ file = Get-Item $FormFile }
        } elseif ($Body) {
            $json = $Body | ConvertTo-Json -Depth 8
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json))
        } else {
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
        }
    } catch {
        return @{ error = $_.Exception.Message; status = $_.Exception.Response.StatusCode.value__; code = -1 }
    }
}
function Save-Json([string]$Name, [object]$Content) {
    ($Content | ConvertTo-Json -Depth 14) | Out-File -Encoding utf8 (Join-Path $OutDir $Name)
}

# ---------------- 前置 ----------------
if (-not $env:MYSQL_PASSWORD -or -not $env:PG_VECTOR_PASSWORD) { Fail "BLOCKED: password env missing" }
$listen = Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue
if ($listen) { Fail "BLOCKED: port 9090 busy (PID $($listen[0].OwningProcess))" }
$preFp = & pwsh -File (Join-Path $scripts "run-fingerprint-d41.ps1") -Phase PRE -OutDir $OutDir
if ($LASTEXITCODE -ne 0) { Fail "BLOCKED: PRE fingerprint failed: $preFp" }
$auditMaxPre = ([regex]::Match((Invoke-Db mysql "SELECT COALESCE(MAX(id),0) AS m FROM d41_admin_audit"), '(?m)^(\d+)\s*$')).Groups[1].Value

# principal 夹具（ADMIN + USER，d41t- 批次；SHA-256 入库，明文仅 temp_docs）
function New-Token([string]$kind) { "$batch-$kind-" + [guid]::NewGuid().ToString("N") }
$script:AdminToken = New-Token "admin"
$userToken = New-Token "user"
function Get-Sha([string]$s) {
    (([System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($s)) | ForEach-Object { $_.ToString("x2") }) -join "")
}
$adminHash = Get-Sha $AdminToken; $userHash = Get-Sha $userToken
# device_scopes 用 JSON_ARRAY 构造（避开 pwsh native 参数传递对反斜杠引号的破坏，DbExec 零转义）
Invoke-Db mysql "INSERT INTO d41_auth_principal (token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled) VALUES ('$adminHash','$batch-admin','T1','D-HQ',JSON_ARRAY(),'ADMIN',1); INSERT INTO d41_auth_principal (token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled) VALUES ('$userHash','$batch-user','T1','D-A',JSON_ARRAY('DEV-CNC-A1'),'USER',1)" | Out-Null
@{ batch = $batch; admin = @{ token = $AdminToken; userId = "$batch-admin" }; user = @{ token = $userToken; userId = "$batch-user" } } |
    ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 (Join-Path $repo "docs\temp_docs\D41\credentials-$batch.json")

# classpath + 启动
& $mvn -o -q -f (Join-Path $module "pom.xml") compile dependency:build-classpath "-Dmdep.outputFile=target\r01-cp.txt" | Out-Null
if (-not (Test-Path (Join-Path $module "target\r01-cp.txt"))) { Fail "BLOCKED: classpath build failed" }
$cp = (Get-Content (Join-Path $module "target\r01-cp.txt") -Raw).Trim()
# D11 偏差裁决：任务书 H2 的 TWO_EMBEDDING_ENABLED=false 在 D41 骨架装配现实下启动即崩
# （TwoPgVectorConfiguration 无条件装配且 requireValid 要求 two.enabled=true）。
# 零远程精神由"演练全程仅使用 LOCAL 主轨向量链、无任何百炼调用点 + 不注入 DASHSCOPE key"保证。
$env:AI_MODE = "mock"
$logDir = Join-Path $OutDir "instances"; New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$proc = Start-Process -FilePath $javaExe -ArgumentList @(
    '-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Dapp.access.enabled=true',
    '-cp', "target\classes;$cp", 'com.zl.d41.D41SpringAiApplication') `
    -WorkingDirectory $module -RedirectStandardOutput (Join-Path $logDir "app.out.log") `
    -RedirectStandardError (Join-Path $logDir "app.err.log") -NoNewWindow -PassThru
$deadline = (Get-Date).AddSeconds(150); $up = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if (Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue) { $up = $true; Start-Sleep -Seconds 2; break }
    if ($proc.HasExited) { Fail "instance exited early, see $logDir\app.err.log" }
}
if (-not $up) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue; Fail "BLOCKED: app not listening within 150s" }

try {
    # ---------------- S1 夹具入库（9 文件） ----------------
    $docMap = [ordered]@{}
    $files = [ordered]@{
        "d41-fx-faultcode.pdf"  = Join-Path $fixDir "pdfs\d41-fx-faultcode.pdf"
        "d41-fx-params.pdf"     = Join-Path $fixDir "pdfs\d41-fx-params.pdf"
        "d41-fx-anomaly-a2.pdf" = Join-Path $fixDir "pdfs\d41-fx-anomaly-a2.pdf"
        "d41-fx-anomaly-a3.pdf" = Join-Path $fixDir "pdfs\d41-fx-anomaly-a3.pdf"
        "d41-fx-anomaly-a4.pdf" = Join-Path $fixDir "pdfs\d41-fx-anomaly-a4.pdf"
        "d41-fx-anomaly-a5.pdf" = Join-Path $fixDir "pdfs\d41-fx-anomaly-a5.pdf"
        "d41-fx-pipe.md"        = Join-Path $fixDir "texts\d41-fx-pipe.md"
        "d41-fx-pipe.txt"       = Join-Path $fixDir "texts\d41-fx-pipe.txt"
        "d41-fx-table.docx"     = Join-Path $fixDir "docx\d41-fx-table.docx"
    }
    $s1 = "PASS"
    foreach ($name in $files.Keys) {
        $reg = Invoke-Api POST "/api/v1/knowledge/documents" @{
            source = "$batch-$name"; title = "$batch-$name"; version = "1"
            tenantId = "T1"; deptId = "D-A"; deviceId = "DEV-CNC-A1" }
        if (-not $reg.data.documentId) { $s1 = "FAIL register ${name}: $($reg | ConvertTo-Json -Depth 5 -Compress)"; break }
        $id = $reg.data.documentId
        $script:DocIds.Add($id)
        $imp = Invoke-Api POST "/api/v1/knowledge/documents/$id/import" -FormFile $files[$name]
        if ($imp.code -ne 200) { $s1 = "FAIL import ${name}: $($imp | ConvertTo-Json -Depth 5 -Compress)"; break }
        $docMap[$name] = $id
    }
    $docMap | ConvertTo-Json | Out-File -Encoding utf8 (Join-Path $OutDir "docmap.json")
    Save-Json "s1-import.json" @{ verdict = $s1; docIds = $docMap }
    $script:Results["S1 夹具入库"] = $s1
    if ($s1 -ne "PASS") { throw $s1 }

    # 每文档 tables 快照（A1–A5 判定的原始证据）
    $tablesSnap = [ordered]@{}
    foreach ($k in $docMap.Keys) { $tablesSnap[$k] = (Invoke-Api GET "/api/v1/knowledge/documents/$($docMap[$k])/tables").data }
    Save-Json "s1-tables-snapshot.json" $tablesSnap

    # ---------------- S2 源表逐格对账（T1–T6 + a5 图像页） ----------------
    $recOut = Join-Path $OutDir "s2-reconcile-report.json"
    $rec = & $javaExe "-Dstdout.encoding=UTF-8" -cp $cp (Join-Path $repo "docs\temp_docs\D41\D41TableReconcile.java") `
        --base $BaseUrl --token $AdminToken `
        --truth (Join-Path $fixDir "truth\d41-fixture-truth.json") `
        --docmap (Join-Path $OutDir "docmap.json") --out $recOut 2>&1
    $s2 = if ($LASTEXITCODE -eq 0) { "PASS" } else { "FAIL: $($rec -join ' | ')" }
    Save-Json "s2-console.txt" ($rec -join "`n")
    $script:Results["S2 逐格对账"] = $s2

    # ---------------- S3 A1–A5 异常矩阵旗标 ----------------
    $s3 = "PASS"; $a = @{}
    function Has-Flag($tables, $flag) {
        ($tables | Where-Object { $_.detectFlags -contains $flag }).Count -ge 1
    }
    $a["A1 DOCX_TABLE_NOT_STRUCTURED"] = Has-Flag $tablesSnap["d41-fx-table.docx"] "DOCX_TABLE_NOT_STRUCTURED"
    $a["A1b docx status DEGRADED"] = (($tablesSnap["d41-fx-table.docx"] | Where-Object { $_.status -eq "DEGRADED" }).Count -eq 1)
    $a["A2 LOW_CONFIDENCE"] = Has-Flag $tablesSnap["d41-fx-anomaly-a2.pdf"] "LOW_CONFIDENCE"
    $a["A2b MERGED_CELL"] = Has-Flag $tablesSnap["d41-fx-anomaly-a2.pdf"] "MERGED_CELL"
    $a["A2c COLUMN_MISALIGNMENT"] = Has-Flag $tablesSnap["d41-fx-anomaly-a2.pdf"] "COLUMN_MISALIGNMENT"
    $a["A3 CONTINUATION"] = Has-Flag $tablesSnap["d41-fx-anomaly-a3.pdf"] "CONTINUATION_HEADER_SKIPPED"
    $a["A4 MISALIGN"] = Has-Flag $tablesSnap["d41-fx-anomaly-a4.pdf"] "COLUMN_MISALIGNMENT"
    $a["A4b END NOTE 界外"] = -not (($tablesSnap["d41-fx-anomaly-a4.pdf"] | ForEach-Object { $_.rowCount } | Measure-Object -Sum).Sum -gt 8)
    $a["A5 IMAGE_TABLE_NOT_PARSED"] = Has-Flag $tablesSnap["d41-fx-anomaly-a5.pdf"] "IMAGE_TABLE_NOT_PARSED"
    $s3Bad = @(foreach ($k in @($a.Keys)) { if ($a[$k] -ne $true) { $k } })
    if ($s3Bad.Count -gt 0) { $s3 = "FAIL: " + ($s3Bad -join ",") }
    Save-Json "s3-anomaly-matrix.json" @{ checks = $a; verdict = $s3 }
    $script:Results["S3 A1–A5 矩阵"] = $s3

    # ---------------- S4 C08 双轨（vector-index → 重抽 linked 命中） ----------------
    $s4 = "PASS"
    $fcId = $docMap["d41-fx-faultcode.pdf"]
    $idx = Invoke-Api POST "/api/v1/knowledge/documents/$fcId/vector-index"
    if ($idx.code -ne 200) { $s4 = "FAIL index: $($idx | ConvertTo-Json -Depth 5 -Compress)" }
    $chunks = (Invoke-Api GET "/api/v1/knowledge/documents/$fcId/vector-index/chunks").data
    $re = Invoke-Api POST "/api/v1/knowledge/documents/$fcId/tables/extract"
    if ($re.code -ne 200) { $s4 = "FAIL extract: $($re | ConvertTo-Json -Depth 5 -Compress)" }
    $tables2 = (Invoke-Api GET "/api/v1/knowledge/documents/$fcId/tables").data
    $linkedCount = ($tables2 | Where-Object { $_.linkedChunkIds.Count -gt 0 }).Count
    $missingCount = ($tables2 | Where-Object { $_.detectFlags -contains "LINKAGE_MISSING" }).Count
    if ($linkedCount -lt 1 -or $missingCount -ne 0) { $s4 = "FAIL linkage linked=$linkedCount missing=$missingCount" }
    # 行文本 ⊆ chunk 文本抽查
    $row0 = (Invoke-Api GET "/api/v1/knowledge/tables/$($tables2[0].id)/rows").data[0]
    $rowTextNorm = ($row0.cells | Where-Object { $null -ne $_.value } | ForEach-Object { $_.value }) -join ' '
    $chunkTexts = ($chunks | ForEach-Object { $_.content }) -join "`n"
    if (-not $chunkTexts.Contains(($rowTextNorm -replace '\s+', ' ').Trim())) { $s4 = "FAIL row text not in chunk: [$rowTextNorm]" }
    Save-Json "s4-dualtrack.json" @{ index = $idx.code; reextract = $re.code; linkedCount = $linkedCount; missingCount = $missingCount; rowSample = $row0 }
    $script:Results["S4 C08 双轨"] = $s4

    # ---------------- S5 重抽幂等 + 审计落行 ----------------
    $s5 = "PASS"
    $cntA = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_extract WHERE document_id='$fcId'"), '(?m)^(\d+)\s*$')).Groups[1].Value
    $re2 = Invoke-Api POST "/api/v1/knowledge/documents/$fcId/tables/extract"
    $cntB = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_extract WHERE document_id='$fcId'"), '(?m)^(\d+)\s*$')).Groups[1].Value
    if ($cntA -ne $cntB -or $re2.code -ne 200) { $s5 = "FAIL idempotent cntA=$cntA cntB=$cntB" }
    $auditRow = Invoke-Db mysql "SELECT action, target_document_id, version, result FROM d41_admin_audit WHERE id > $auditMaxPre AND action='TABLE_EXTRACT'"
    if ($auditRow -notmatch "TABLE_EXTRACT") { $s5 = "FAIL audit row missing" }
    Save-Json "s5-idempotent-audit.json" @{ cntA = $cntA; cntB = $cntB; audit = $auditRow }
    $script:Results["S5 重抽幂等+审计"] = $s5

    # ---------------- S6 C09 权限面（USER/无 token/跨部门） ----------------
    $s6Checks = [ordered]@{}
    $uOk = Invoke-Api GET "/api/v1/knowledge/documents/$fcId/tables" -Token $userToken
    $s6Checks["USER 同域读=200"] = ($uOk.code -eq 200)
    # 跨部门文档（D-B）：新生成内容避免 contentHash 去重
    $scopeFile = Join-Path $env:TEMP "$batch-scope.md"
    (Get-Content $files["d41-fx-pipe.md"] -Raw -Encoding UTF8) + "`n| scope | note |`n|------|------|`n| B1 | $batch cross dept |`n" |
        Out-File -Encoding utf8 $scopeFile
    $regB = Invoke-Api POST "/api/v1/knowledge/documents" @{
        source = "$batch-cross"; title = "$batch-cross"; version = "1"
        tenantId = "T1"; deptId = "D-B"; deviceId = "DEV-CNC-B1" }
    $idB = $regB.data.documentId; $script:DocIds.Add($idB)
    $impB = Invoke-Api POST "/api/v1/knowledge/documents/$idB/import" -FormFile $scopeFile
    $s6Checks["跨部门文档入库"] = ($impB.code -eq 200)
    $forb = Invoke-Api GET "/api/v1/knowledge/documents/$idB/tables" -Token $userToken
    # RetrievalException 经 GlobalExceptionHandler 返回 code=4001+固定 msg（reason 码不外泄，
    # 服务端 WARN 日志留 AUTH_* trace），断言按响应 msg 文本+非200 判定
    $s6Checks["跨部门读拒绝"] = ($forb.code -ne 200 -and ($forb | ConvertTo-Json -Compress) -match "无权访问该文档|AUTH_FORBIDDEN")
    $uExt = Invoke-Api POST "/api/v1/knowledge/documents/$idB/tables/extract" -Token $userToken
    $s6Checks["USER 重抽拒绝"] = ($uExt.code -ne 200 -and ($uExt | ConvertTo-Json -Compress) -match "仅限管理员|AUTH_FORBIDDEN")
    $noTok = Invoke-Api GET "/api/v1/knowledge/documents/$fcId/tables" -Token ""
    $s6Checks["无 token 拒绝"] = ($noTok.code -ne 200)
    Remove-Item $scopeFile -Force -ErrorAction SilentlyContinue
    $s6 = if (($s6Checks.Values | Where-Object { $_ -eq $false }).Count -eq 0) { "PASS" } else { "FAIL: $(($s6Checks.GetEnumerator() | Where-Object { -not $_.Value } | ForEach-Object { $_.Key }) -join ',')" }
    Save-Json "s6-permission.json" @{ checks = $s6Checks; verdict = $s6 }
    $script:Results["S6 C09 权限"] = $s6

    # ---------------- S7 TABLE_ORPHAN 检出 ----------------
    $s7 = "PASS"
    $orphanDoc = "$batch-orphan-demo"
    Invoke-Db mysql "INSERT INTO d41_table_extract (document_id, version, page_number, table_index, header_snapshot_json, row_count, col_count, detect_flags, confidence, status, created_at, updated_at) VALUES ('$orphanDoc', 99, 1, 0, '[]', 1, 1, '', 'HIGH', 'ACTIVE', NOW(), NOW())" | Out-Null
    $rec7 = Invoke-Api POST "/api/v1/knowledge/vector-index/reconcile?dryRun=true"
    $orphans = $rec7.data.tableOrphans
    $s7Msg = @()
    if ($null -eq $orphans -or @($orphans | Where-Object { $_.documentId -eq $orphanDoc }).Count -lt 1) {
        $s7Msg += "tableOrphans 未检出 $orphanDoc (code=$($rec7.code) msg=$($rec7.msg))"
    }
    if ($rec7.data.dryRun -ne $true) { $s7Msg += "dryRun 未生效" }
    if ($s7Msg.Count -gt 0) { $s7 = "FAIL: " + ($s7Msg -join "; ") }
    Save-Json "s7-table-orphan.json" @{ orphanDoc = $orphanDoc; tableOrphans = $orphans; totalIssues = $rec7.data.totalIssues }
    # 演示行按名清除（本批自插行，非业务数据）
    Invoke-Db mysql "DELETE FROM d41_table_extract WHERE document_id='$orphanDoc'" | Out-Null
    $script:Results["S7 TABLE_ORPHAN"] = $s7

    # ---------------- S8 删除闭环绑定（D7：向量删除事务清表） ----------------
    $s8 = "PASS"
    $del = Invoke-Api DELETE "/api/v1/knowledge/documents/$fcId/vector-index?version=1"
    if ($del.code -ne 200) { $s8 = "FAIL delete: $($del | ConvertTo-Json -Depth 5 -Compress)" }
    $tCnt = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_extract WHERE document_id='$fcId'"), '(?m)^(\d+)\s*$')).Groups[1].Value
    $rCnt = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_row tr JOIN d41_table_extract te ON tr.table_id=te.id WHERE te.document_id='$fcId'"), '(?m)^(\d+)\s*$')).Groups[1].Value
    if ($tCnt -ne 0 -or $rCnt -ne 0) { $s8 = "FAIL table residual t=$tCnt r=$rCnt" }
    $syncRow = Invoke-Db mysql "SELECT sync_status FROM d41_document_sync WHERE document_id='$fcId' AND operation='DELETE' AND version='1'"
    Save-Json "s8-delete-binding.json" @{ delete = $del; extractResidual = $tCnt; rowResidual = $rCnt; syncRow = $syncRow }
    $script:Results["S8 删除闭环绑定"] = $s8
}
finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    # 防残留兜底（幂等，仅在已注册文档非空时执行；正式核验仍在 S9）
    if ($script:DocIds.Count -gt 0) {
        $dl = ($script:DocIds | ForEach-Object { "'$_'" }) -join ","
        Invoke-Db mysql "DELETE FROM knowledge_document_page WHERE document_id IN ($dl); DELETE FROM knowledge_document_content WHERE document_id IN ($dl); DELETE FROM vector_chunk_index WHERE document_id IN ($dl); DELETE FROM vector_index_task WHERE document_id IN ($dl); DELETE FROM d41_table_row WHERE table_id IN (SELECT id FROM d41_table_extract WHERE document_id IN ($dl)); DELETE FROM d41_table_extract WHERE document_id IN ($dl); DELETE FROM d41_document_sync WHERE document_id COLLATE utf8mb4_general_ci IN ($dl); DELETE FROM knowledge_document WHERE document_id IN ($dl); DELETE FROM d41_auth_principal WHERE user_id LIKE 'd41t-%'; DELETE FROM d41_admin_audit WHERE actor_user_id LIKE 'd41t-%'" 2>&1 | Out-Null
        Invoke-Db pg "DELETE FROM vector_store WHERE metadata->>'documentId' IN ($dl)" 2>&1 | Out-Null
    }
}

# ---------------- S9 清理（本批自建行精确清除） + POST 指纹 ----------------
$s9 = "PASS"
$docList = ($script:DocIds | ForEach-Object { "'$_'" }) -join ","
Invoke-Db mysql "DELETE FROM d41_table_row WHERE table_id IN (SELECT id FROM d41_table_extract WHERE document_id IN ($docList)); DELETE FROM d41_table_extract WHERE document_id IN ($docList); DELETE FROM knowledge_document_page WHERE document_id IN ($docList); DELETE FROM knowledge_document_content WHERE document_id IN ($docList); DELETE FROM vector_chunk_index WHERE document_id IN ($docList); DELETE FROM vector_index_task WHERE document_id IN ($docList); DELETE FROM d41_document_sync WHERE document_id COLLATE utf8mb4_general_ci IN ($docList); DELETE FROM knowledge_document WHERE document_id IN ($docList); DELETE FROM d41_admin_audit WHERE id > $auditMaxPre; DELETE FROM d41_auth_principal WHERE token_sha256 IN ('$adminHash','$userHash'); DELETE FROM d41_auth_principal WHERE user_id LIKE 'd41t-%'; DELETE FROM d41_admin_audit WHERE actor_user_id LIKE 'd41t-%'" | Out-Null
# PG 主轨本批向量清除（S8 闭环外的 C08 残留收敛；documentId 为 UUID 精确域）
Invoke-Db pg "DELETE FROM vector_store WHERE metadata->>'documentId' IN ($docList)" | Out-Null
$docLeft = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM knowledge_document WHERE document_id IN ($docList)"), '(?m)^(\d+)\s*$')).Groups[1].Value
$pgLeft = ([regex]::Match((Invoke-Db pg "SELECT count(*) AS c FROM vector_store"), '(?m)^(\d+)\s*$')).Groups[1].Value
$teTotal = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_extract"), '(?m)^(\d+)\s*$')).Groups[1].Value
$trTotal = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_row"), '(?m)^(\d+)\s*$')).Groups[1].Value
$prTotal = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_auth_principal"), '(?m)^(\d+)\s*$')).Groups[1].Value
$auditTotal = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_admin_audit"), '(?m)^(\d+)\s*$')).Groups[1].Value
if ($teTotal -ne 0 -or $trTotal -ne 0 -or $prTotal -ne 4 -or $auditTotal -ne [int]$auditMaxPre -or $docLeft -ne 0 -or $pgLeft -ne 30) {
    $s9 = "FAIL residual te=$teTotal tr=$trTotal principal=$prTotal audit=$auditTotal(maxPre=$auditMaxPre) doc=$docLeft pg=$pgLeft"
}
# PG 冻结核验（C08 的 d41 PDF 向量已由 S8 删除闭环收敛）
$postFp = & pwsh -File (Join-Path $scripts "run-fingerprint-d41.ps1") -Phase POST -OutDir $OutDir
Save-Json "s9-cleanup-post-fingerprint.json" @{ cleanup = @{ te = $teTotal; tr = $trTotal; principal = $prTotal; audit = $auditTotal }; postFingerprint = ($postFp -join "`n") }
$script:Results["S9 清理+POST 指纹"] = $s9
$script:Results | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 (Join-Path $OutDir "final-verdict-r01.json")
Write-Host "== D41 R01 演练结果 =="
$script:Results.GetEnumerator() | ForEach-Object { Write-Host "$($_.Key): $($_.Value)" }
$failed = ($script:Results.Values | Where-Object { $_ -ne "PASS" }).Count
exit $(if ($failed -eq 0) { 0 } else { 1 })

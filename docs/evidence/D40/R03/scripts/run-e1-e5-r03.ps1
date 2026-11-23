# =============================================================================
# D40 R03-06 E1–E5 异常演练可执行脚本（实体化，替代 R01 注释占位版）
# 旧脚本 docs/evidence/D40/scripts/run-e1-e5-execution.ps1 的执行入口自本轮废弃。
#
# 用法（密钥仅经环境变量注入，禁止硬编码/落盘/提交）：
#   $env:MYSQL_PASSWORD=...; $env:PG_VECTOR_PASSWORD=...
#   pwsh -File run-e1-e5-r03.ps1 -JavaExe <java21> -MysqlJar <connector-j> `
#        -PgJar <postgresql-jdbc> -MavenCmd <mvn.cmd> -ModuleDir <D40_DUDACR>
#
# 数据安全：全部演练数据经 source=d40t-r03-* 批次标识 + documentId 清单精确隔离；
# 不 DROP/TRUNCATE 业务表；清理仅删除本批次创建的行；结束回归 PRE/POST 指纹。
# 退出码：0=全部场景 PASS；1=存在 FAIL/BLOCKED。
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:9090",
    [string]$OutDir = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D40\R03\e1-e5",
    [string]$JavaExe = $(if ($env:JAVA21_EXE) { $env:JAVA21_EXE } else { "java" }),
    [string]$MysqlJar = $(if ($env:MYSQL_CONNECTOR_JAR) { $env:MYSQL_CONNECTOR_JAR } else { "" }),
    [string]$PgJar = $(if ($env:PG_JDBC_JAR) { $env:PG_JDBC_JAR } else { "" }),
    [string]$MavenCmd = $(if ($env:MAVEN_CMD) { $env:MAVEN_CMD } else { "mvn" }),
    [string]$ModuleDir = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D40\D40_DUDACR",
    [string[]]$Scenarios = @("E1", "E2", "E3", "E4", "E5")
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$batch = "d40t-r03-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$script:DbExecPath = Join-Path $PSScriptRoot "DbExec.java"
$script:GenPdfPath = Join-Path $PSScriptRoot "GenPdf.java"
$repo = if ($env:MAVEN_REPO_ROOT) { $env:MAVEN_REPO_ROOT } else { "D:\SoftwareInstall\Apache\repository" }
$script:PdfboxCp = @(
    "$repo\org\apache\pdfbox\pdfbox\3.0.7\pdfbox-3.0.7.jar",
    "$repo\org\apache\pdfbox\fontbox\3.0.7\fontbox-3.0.7.jar",
    "$repo\org\apache\pdfbox\pdfbox-io\3.0.7\pdfbox-io-3.0.7.jar",
    "$repo\commons-logging\commons-logging\1.3.5\commons-logging-1.3.5.jar"
) -join ";"
$script:DocIds = [System.Collections.Generic.List[string]]::new()
$script:Results = [ordered]@{}

function Fail([string]$msg) { throw $msg }

function Invoke-Db {
    param([ValidateSet("mysql", "pg")][string]$Target, [string]$Sql)
    $jar = if ($Target -eq "mysql") { $MysqlJar } else { $PgJar }
    if (-not $jar -or -not (Test-Path $jar)) { Fail "BLOCKED: JDBC driver jar missing for $Target" }
    $out = & $JavaExe '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $jar $script:DbExecPath $Target -e $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "DB exec failed ($Target): $out" }
    return ($out -join "`n")
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [string]$FormFile)
    $headers = @{ Authorization = "Bearer $script:Token" }
    $uri = "$BaseUrl$Path"
    try {
        if ($FormFile) {
            $resp = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Form @{ file = Get-Item $FormFile }
        } elseif ($Body) {
            $json = $Body | ConvertTo-Json -Depth 8
            $resp = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $json
        } else {
            $resp = Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
        }
        return $resp
    } catch {
        return @{ error = $_.Exception.Message; status = $_.Exception.Response.StatusCode.value__ }
    }
}

function Save-Evidence {
    param([string]$Scenario, [string]$Name, [object]$Content)
    $dir = Join-Path $OutDir $Scenario
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $text = if ($Content -is [string]) { $Content } else { $Content | ConvertTo-Json -Depth 12 }
    $text | Out-File -Encoding utf8 (Join-Path $dir "$Name.json")
}

function Get-Fingerprint {
    $pg = Invoke-Db pg "SELECT (SELECT COUNT(*) FROM vector_store) AS vector_store, (SELECT COUNT(*) FROM two_vector_store) AS two_vector_store"
    $my = Invoke-Db mysql "SELECT (SELECT COUNT(*) FROM knowledge_document) AS knowledge_document, (SELECT COUNT(*) FROM vector_chunk_index) AS vector_chunk_index, (SELECT COUNT(*) FROM vector_index_task) AS vector_index_task, (SELECT COUNT(*) FROM d40_document_sync) AS d40_document_sync, (SELECT COUNT(*) FROM d40_admin_audit) AS d40_admin_audit, (SELECT COUNT(*) FROM d40_auth_principal) AS d40_auth_principal"
    return @{ pg = $pg; mysql = $my }
}

function Start-Instance {
    param([string]$JvmArgs, [string]$LogName)
    $logDir = Join-Path $OutDir "instances"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $cpFile = Join-Path $ModuleDir "target\r03-classpath.txt"
    if (-not (Test-Path $cpFile)) {
        & $MavenCmd -o -q compile dependency:build-classpath "-Dmdep.outputFile=target/r03-classpath.txt" -f (Join-Path $ModuleDir "pom.xml") | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { Fail "BLOCKED: classpath build failed" }
    }
    $cp = (Get-Content $cpFile -Raw).Trim()
    $env:AI_MODE = "mock"
    $jvm = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8') + ($JvmArgs -split ' ')
    $proc = Start-Process -FilePath $JavaExe -ArgumentList ($jvm + @('-cp', "target\classes;$cp", 'com.zl.d40.D40SpringAiApplication')) `
        -WorkingDirectory $ModuleDir `
        -RedirectStandardOutput (Join-Path $logDir "$LogName.out.log") `
        -RedirectStandardError (Join-Path $logDir "$LogName.err.log") -NoNewWindow -PassThru
    $script:JavaPid = $proc.Id
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 3
        $listen = Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue
        if ($listen) { Start-Sleep -Seconds 2; return }
        if ($proc.HasExited) { Fail "instance exited early, see $logDir/$LogName.err.log" }
    }
    Fail "BLOCKED: instance did not listen on 9090 within 120s"
}

function Stop-Instance {
    if ($script:JavaPid) { Stop-Process -Id $script:JavaPid -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
    $script:JavaPid = $null
}

function New-TestDoc {
    param([string]$Tag, [string]$Version, [int]$Pages = 1, [int]$CharsPerPage = 600)
    $reg = Invoke-Api POST "/api/v1/knowledge/documents" @{ source = "$batch-$Tag"; title = "$batch-$Tag"; version = $Version }
    if (-not $reg.data.documentId) { Fail "register failed: $($reg | ConvertTo-Json -Depth 5)" }
    $docId = $reg.data.documentId
    $script:DocIds.Add($docId)
    $pdf = Join-Path $env:TEMP "$batch-$Tag.pdf"
    & $JavaExe -cp $script:PdfboxCp $script:GenPdfPath $pdf $Pages $CharsPerPage | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "pdf generation failed for $Tag" }
    $imp = Invoke-Api POST "/api/v1/knowledge/documents/$docId/import" -FormFile $pdf
    Remove-Item $pdf -Force -ErrorAction SilentlyContinue
    if ($imp.code -ne 200) { Fail "import failed: $($imp | ConvertTo-Json -Depth 5)" }
    return @{ documentId = $docId; register = $reg; import = $imp }
}

function Get-SyncRow {
    param([string]$DocId, [string]$Operation, [string]$Version)
    $v = if ($Version) { $Version } else { "" }
    $sql = "SELECT document_id, operation, version, sync_status, reason_code, attempt_count, next_retry_at, payload_hash FROM d40_document_sync WHERE document_id='$DocId' AND operation='$Operation' AND version='$v'"
    return Invoke-Db mysql $sql
}

function Get-PgCount {
    param([string]$DocId, [string]$Version)
    $where = if ($Version) { "metadata->>'documentId'='$DocId' AND metadata->>'version'='$Version'" } else { "metadata->>'documentId'='$DocId'" }
    return Invoke-Db pg "SELECT COUNT(*) AS c FROM vector_store WHERE $where"
}

# ---------------- 前置检查 ----------------
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
if (-not $env:MYSQL_PASSWORD) { Fail "BLOCKED: MYSQL_PASSWORD env missing" }
if (-not $env:PG_VECTOR_PASSWORD) { Fail "BLOCKED: PG_VECTOR_PASSWORD env missing" }
$listen = Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue
if ($listen) { Fail "BLOCKED: port 9090 already in use (PID $($listen[0].OwningProcess))" }

$pre = Get-Fingerprint
$pre | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 (Join-Path $OutDir "fingerprint-pre.json")

# 临时 ADMIN principal（d40t- 前缀，增量插入，不触碰预存 4 行）
$script:Token = "$batch-token-" + [guid]::NewGuid().ToString("N")
$hash = ([System.Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($script:Token)) | ForEach-Object { $_.ToString("x2") }) -join ""
Invoke-Db mysql "INSERT INTO d40_auth_principal (token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled) VALUES ('$hash', '$batch-admin', 'T1', 'D-HQ', '[]', 'ADMIN', 1)" | Out-Null
$script:AdminUserId = "$batch-admin"

$baseJvm = "-Dapp.access.enabled=true -Dapp.vector-index.sync-retry.enabled=true -Dapp.vector-index.retry-backoff-ms=500"

try {
    # ---------------- E1：PG 删除失败 → 重试收敛 ----------------
    if ($Scenarios -contains "E1") {
        $s = "E1"; $verdict = "PASS"
        try {
            Start-Instance "$baseJvm -Dapp.vector-index.fault-injection.enabled=true -Dapp.vector-index.fault-injection.fail-delete-call=1" "E1"
            $doc = New-TestDoc "e1" "1"
            $id = $doc.documentId
            $idx = Invoke-Api POST "/api/v1/knowledge/documents/$id/vector-index"
            if ($idx.code -ne 200) { $verdict = "FAIL: index failed: $($idx.msg)" }
            Save-Evidence $s "01-setup" @{ batch = $batch; documentId = $id; register = $doc.register; import = $doc.import; index = $idx; injection = "fail-delete-call=1" }
            $pgBefore = Get-PgCount $id "1"
            if ($pgBefore -match "(?m)^0\s*$") { $verdict = "FAIL: no vectors indexed before delete" }
            $del = Invoke-Api DELETE "/api/v1/knowledge/documents/$id/vector-index?version=1"
            $row1 = Get-SyncRow $id "DELETE" "1"
            Save-Evidence $s "02-fault-delete" @{ request = "DELETE /api/v1/knowledge/documents/$id/vector-index?version=1"; response = $del; syncRow = $row1; pgCount = $pgBefore }
            if ($row1 -notmatch "FAILED" -or $row1 -notmatch "PG_UNREACHABLE") { $verdict = "FAIL: expected FAILED/PG_UNREACHABLE" }
            $retry = Invoke-Api POST "/api/v1/knowledge/sync/retry" @{ documentId = $id; operation = "DELETE"; version = "1" }
            $row2 = Get-SyncRow $id "DELETE" "1"
            $pgAfter = Get-PgCount $id "1"
            $relAfter = Invoke-Db mysql "SELECT COUNT(*) AS c FROM vector_chunk_index WHERE document_id='$id' AND version='1'"
            Save-Evidence $s "03-recovery" @{ retryResponse = $retry; syncRow = $row2; pgResidual = $pgAfter; relationResidual = $relAfter }
            if ($row2 -notmatch "SYNCED") { $verdict = "FAIL: retry not SYNCED" }
            if ($pgAfter -notmatch "(?m)^0\s*$" -or $relAfter -notmatch "(?m)^0\s*$") { $verdict = "FAIL: residual not zero" }
        } catch { $verdict = "FAIL: $($_.Exception.Message)" } finally { Stop-Instance }
        $script:Results[$s] = $verdict
        Save-Evidence $s "verdict" @{ scenario = $s; batch = $batch; result = $verdict }
    }

    # ---------------- E2：部分向量写入失败 → 重试收敛 ----------------
    if ($Scenarios -contains "E2") {
        $s = "E2"; $verdict = "PASS"
        try {
            Start-Instance "$baseJvm -Dapp.vector-index.batch-size=1 -Dapp.vector-index.fault-injection.enabled=true -Dapp.vector-index.fault-injection.fail-add-call=2" "E2"
            $doc = New-TestDoc "e2" "1" 3 600
            $id = $doc.documentId
            $idx = Invoke-Api POST "/api/v1/knowledge/documents/$id/vector-index"
            $status1 = Invoke-Api GET "/api/v1/knowledge/documents/$id/vector-index"
            $pgPartial = Get-PgCount $id "1"
            $task1 = Invoke-Db mysql "SELECT status, stage, error_code, attempt_count, chunk_count FROM vector_index_task WHERE document_id='$id' AND version='1'"
            Save-Evidence $s "01-partial-failure" @{ batch = $batch; documentId = $id; indexResponse = $idx; taskStatus = $status1; taskRow = $task1; pgWritten = $pgPartial; injection = "batch-size=1, fail-add-call=2" }
            if ($task1 -notmatch "PARTIAL") { $verdict = "FAIL: expected PARTIAL" }
            $retry = Invoke-Api POST "/api/v1/knowledge/documents/$id/vector-index/retry"
            $task2 = Invoke-Db mysql "SELECT status, stage, error_code, attempt_count, chunk_count FROM vector_index_task WHERE document_id='$id' AND version='1'"
            $pgFinal = Get-PgCount $id "1"
            $relFinal = Invoke-Db mysql "SELECT COUNT(*) AS c FROM vector_chunk_index WHERE document_id='$id' AND version='1'"
            $indexedFinal = Invoke-Db mysql "SELECT COUNT(*) AS c FROM vector_chunk_index WHERE document_id='$id' AND version='1' AND status='INDEXED'"
            Save-Evidence $s "02-recovery" @{ retryResponse = $retry; taskRow = $task2; pgCount = $pgFinal; relationCount = $relFinal; indexedCount = $indexedFinal }
            if ($task2 -notmatch "COMPLETED") { $verdict = "FAIL: retry not COMPLETED" }
        } catch { $verdict = "FAIL: $($_.Exception.Message)" } finally { Stop-Instance }
        $script:Results[$s] = $verdict
        Save-Evidence $s "verdict" @{ scenario = $s; batch = $batch; result = $verdict }
    }

    # ---------------- E3：旧版本清理失败 → UPDATE_CLEANUP 恢复 ----------------
    if ($Scenarios -contains "E3") {
        $s = "E3"; $verdict = "PASS"
        try {
            Start-Instance "$baseJvm -Dapp.vector-index.fault-injection.enabled=true -Dapp.vector-index.fault-injection.fail-delete-call=1" "E3"
            $doc = New-TestDoc "e3" "1"
            $id = $doc.documentId
            $idx3 = Invoke-Api POST "/api/v1/knowledge/documents/$id/vector-index"
            if ($idx3.code -ne 200) { $verdict = "FAIL: E3 index failed: $($idx3.msg)" }
            # 场景构造（仅测试行）：v1 任务标记 SUPERSEDED + 指针推进到 2 + UPDATE_CLEANUP 失败遗留行
            Invoke-Db mysql "UPDATE vector_index_task SET status='SUPERSEDED' WHERE document_id='$id' AND version='1'; UPDATE knowledge_document SET version='2' WHERE document_id='$id'; INSERT INTO d40_document_sync (document_id, operation, version, sync_status, reason_code, attempt_count, next_retry_at, payload_hash, created_at, updated_at) VALUES ('$id', 'UPDATE_CLEANUP', '1', 'FAILED', 'PG_DELETE_FAILED', 1, DATE_SUB(NOW(6), INTERVAL 10 SECOND), 'e3-construct', NOW(6), DATE_SUB(NOW(6), INTERVAL 10 MINUTE))" | Out-Null
            $preRow = Get-SyncRow $id "UPDATE_CLEANUP" "1"
            Save-Evidence $s "01-construct" @{ batch = $batch; documentId = $id; syncRow = $preRow; note = "v1 SUPERSEDED + pointer=2 + UPDATE_CLEANUP FAILED 遗留行（测试行构造）" }
            $r1 = Invoke-Api POST "/api/v1/knowledge/sync/retry" @{ documentId = $id; operation = "UPDATE_CLEANUP"; version = "1" }
            $row1 = Get-SyncRow $id "UPDATE_CLEANUP" "1"
            Save-Evidence $s "02-fault-retry" @{ response = $r1; syncRow = $row1; injection = "fail-delete-call=1" }
            if ($row1 -notmatch "FAILED") { $verdict = "FAIL: first recovery should stay FAILED" }
            $attempt1 = ((($row1 -split "`n") | Where-Object { $_ -match ("^" + [regex]::Escape($id) + "`t") } | Select-Object -First 1) -split "`t")[5]
            if ($attempt1 -ne "2") { $verdict = "FAIL: attempt should be 2, got $attempt1" }
            $r2 = Invoke-Api POST "/api/v1/knowledge/sync/retry" @{ documentId = $id; operation = "UPDATE_CLEANUP"; version = "1" }
            $row2 = Get-SyncRow $id "UPDATE_CLEANUP" "1"
            $pgOld = Get-PgCount $id "1"
            $relOld = Invoke-Db mysql "SELECT COUNT(*) AS c FROM vector_chunk_index WHERE document_id='$id' AND version='1'"
            $pointer = Invoke-Db mysql "SELECT version FROM knowledge_document WHERE document_id='$id'"
            Save-Evidence $s "03-recovery" @{ response = $r2; syncRow = $row2; pgOldResidual = $pgOld; relationOldResidual = $relOld; pointer = $pointer }
            if ($row2 -notmatch "SYNCED") { $verdict = "FAIL: cleanup not converged" }
            if ($pgOld -notmatch "(?m)^0\s*$" -or $relOld -notmatch "(?m)^0\s*$") { $verdict = "FAIL: old version residual" }
            if ($pointer -notmatch "(?m)^2\s*$") { $verdict = "FAIL: active pointer disturbed" }
        } catch { $verdict = "FAIL: $($_.Exception.Message)" } finally { Stop-Instance }
        $script:Results[$s] = $verdict
        Save-Evidence $s "verdict" @{ scenario = $s; batch = $batch; result = $verdict }
    }

    # ---------------- E4：重复事件 + PENDING 租约恢复 + 并发竞争 ----------------
    if ($Scenarios -contains "E4") {
        $s = "E4"; $verdict = "PASS"
        try {
            Start-Instance $baseJvm "E4-E5"
            $doc = New-TestDoc "e4" "1"
            $id = $doc.documentId
            $idx4 = Invoke-Api POST "/api/v1/knowledge/documents/$id/vector-index"
            if ($idx4.code -ne 200) { $verdict = "FAIL: E4 index failed: $($idx4.msg)" }
            $d1 = Invoke-Api DELETE "/api/v1/knowledge/documents/$id/vector-index?version=1"
            $d2 = Invoke-Api DELETE "/api/v1/knowledge/documents/$id/vector-index?version=1"
            $rows = Invoke-Db mysql "SELECT COUNT(*) AS c FROM d40_document_sync WHERE document_id='$id'"
            Save-Evidence $s "01-duplicate-delete" @{ documentId = $id; first = $d1; second = $d2; syncRowCount = $rows }
            if ($rows -notmatch "(?m)^1\s*$") { $verdict = "FAIL: duplicate rows created" }
            # 过期 PENDING 恢复（模拟认领后崩溃）
            $ghost = "$batch-e4-ghost"
            Invoke-Db mysql "INSERT INTO d40_document_sync (document_id, operation, version, sync_status, reason_code, attempt_count, next_retry_at, payload_hash, created_at, updated_at) VALUES ('$ghost', 'DELETE', '', 'PENDING', '', 1, DATE_SUB(NOW(6), INTERVAL 10 SECOND), 'e4', NOW(6), DATE_SUB(NOW(6), INTERVAL 10 MINUTE))" | Out-Null
            $rec = Invoke-Api POST "/api/v1/knowledge/sync/retry" @{ documentId = $ghost; operation = "DELETE"; version = "" }
            $ghostRow = Get-SyncRow $ghost "DELETE" ""
            Save-Evidence $s "02-lease-recovery" @{ ghost = $ghost; response = $rec; syncRow = $ghostRow }
            if ($ghostRow -notmatch "SYNCED") { $verdict = "FAIL: expired PENDING not recovered" }
            # 并发竞争：两个执行者同时重试同一过期 PENDING
            $ghost2 = "$batch-e4-race"
            Invoke-Db mysql "INSERT INTO d40_document_sync (document_id, operation, version, sync_status, reason_code, attempt_count, next_retry_at, payload_hash, created_at, updated_at) VALUES ('$ghost2', 'DELETE', '', 'PENDING', '', 1, DATE_SUB(NOW(6), INTERVAL 10 SECOND), 'e4', NOW(6), DATE_SUB(NOW(6), INTERVAL 10 MINUTE))" | Out-Null
            $tokenLocal = $script:Token; $baseLocal = $BaseUrl
            $j1 = Start-ThreadJob -ScriptBlock { param($b, $t, $g) Invoke-RestMethod -Method POST -Uri "$b/api/v1/knowledge/sync/retry" -Headers @{ Authorization = "Bearer $t" } -ContentType 'application/json' -Body (@{ documentId = $g; operation = "DELETE"; version = "" } | ConvertTo-Json) } -ArgumentList $baseLocal, $tokenLocal, $ghost2
            $j2 = Start-ThreadJob -ScriptBlock { param($b, $t, $g) Invoke-RestMethod -Method POST -Uri "$b/api/v1/knowledge/sync/retry" -Headers @{ Authorization = "Bearer $t" } -ContentType 'application/json' -Body (@{ documentId = $g; operation = "DELETE"; version = "" } | ConvertTo-Json) } -ArgumentList $baseLocal, $tokenLocal, $ghost2
            $res1 = Receive-Job $j1 -Wait -AutoRemoveJob
            $res2 = Receive-Job $j2 -Wait -AutoRemoveJob
            $raceRow = Get-SyncRow $ghost2 "DELETE" ""
            Save-Evidence $s "03-concurrent-race" @{ executor1 = $res1; executor2 = $res2; finalRow = $raceRow }
            $outcomes = @($res1.data, $res2.data)
            $syncedCount = ($outcomes | Where-Object { $_ -eq "SYNCED" }).Count
            if ($syncedCount -lt 1) { $verdict = "FAIL: no executor converged" }
            if ($raceRow -notmatch "SYNCED") { $verdict = "FAIL: race final not SYNCED" }
            # ---------------- E5：版本指针漂移 → 核查发现 → 管线修复 ----------------
            if ($Scenarios -contains "E5") {
                $s5 = "E5"; $v5 = "PASS"
                try {
                    $doc5 = New-TestDoc "e5" "1"
                    $id5 = $doc5.documentId
                    $idx5 = Invoke-Api POST "/api/v1/knowledge/documents/$id5/vector-index"
                    if ($idx5.code -ne 200) { $v5 = "FAIL: E5 index failed: $($idx5.msg)" }
                    Invoke-Db mysql "UPDATE knowledge_document SET version='9' WHERE document_id='$id5'" | Out-Null
                    $chk1 = Invoke-Api POST "/api/v1/knowledge/sync/consistency?dryRun=true"
                    $before = Invoke-Db mysql "SELECT document_id, version FROM knowledge_document WHERE document_id='$id5'"
                    Save-Evidence $s5 "01-drift-detected" @{ documentId = $id5; pointerBefore = $before; consistency = $chk1 }
                    $found = ($chk1.data.versionMismatches | Where-Object { $_.documentId -eq $id5 }).Count
                    if ($found -lt 1) { $v5 = "FAIL: drift not detected" }
                    # 修复走正式管线：删除闭环清任务/向量 → 按当前指针重建
                    Invoke-Api DELETE "/api/v1/knowledge/documents/$id5/vector-index" | Out-Null
                    $rebuild = Invoke-Api POST "/api/v1/knowledge/documents/$id5/vector-index"
                    $chk2 = Invoke-Api POST "/api/v1/knowledge/sync/consistency?dryRun=true"
                    $after = Invoke-Db mysql "SELECT document_id, version FROM knowledge_document WHERE document_id='$id5'"
                    Save-Evidence $s5 "02-repair" @{ rebuild = $rebuild; pointerAfter = $after; consistency = $chk2 }
                    $still = ($chk2.data.versionMismatches | Where-Object { $_.documentId -eq $id5 }).Count
                    if ($still -ne 0) { $v5 = "FAIL: drift persists after repair" }
                } catch { $v5 = "FAIL: $($_.Exception.Message)" }
                $script:Results[$s5] = $v5
                Save-Evidence $s5 "verdict" @{ scenario = $s5; batch = $batch; result = $v5 }
            }
        } catch { $verdict = "FAIL: $($_.Exception.Message)" } finally { Stop-Instance }
        $script:Results[$s] = $verdict
        Save-Evidence $s "verdict" @{ scenario = $s; batch = $batch; result = $verdict }
    }
} finally {
    # ---------------- 安全清理（仅本批次数据） ----------------
    Stop-Instance
    $cleanupLog = @()
    foreach ($id in $script:DocIds) {
        try {
            $h = @{ Authorization = "Bearer $script:Token" }
            Invoke-RestMethod -Method DELETE -Uri "$BaseUrl/api/v1/knowledge/documents/$id/vector-index" -Headers $h -ErrorAction SilentlyContinue | Out-Null
        } catch { }
    }
    # 服务已停，清理走 JDBC（精确 documentId 清单 + 批次前缀）
    if ($script:DocIds.Count -gt 0) {
        $inList = ($script:DocIds | ForEach-Object { "'$_'" }) -join ","
        $cleanupLog += Invoke-Db mysql "DELETE FROM vector_chunk_index WHERE document_id IN ($inList); DELETE FROM vector_index_task WHERE document_id IN ($inList); DELETE FROM d40_document_sync WHERE document_id IN ($inList); DELETE FROM knowledge_document WHERE document_id IN ($inList); DELETE FROM d40_admin_audit WHERE target_document_id IN ($inList) OR actor_user_id='$script:AdminUserId'"
        # documentId 为服务端 UUID（无批次前缀），PG 清理必须按本批次 documentId 清单精确删除
        $cleanupLog += Invoke-Db pg "DELETE FROM vector_store WHERE metadata->>'documentId' IN ($inList)"
    }
    $cleanupLog += Invoke-Db mysql "DELETE FROM d40_document_sync WHERE document_id LIKE '$batch%'; DELETE FROM d40_auth_principal WHERE user_id='$script:AdminUserId'"
    $cleanupLog += Invoke-Db pg "DELETE FROM vector_store WHERE metadata->>'documentId' LIKE '$batch%'"
    $cleanupLog -join "`n" | Out-File -Encoding utf8 (Join-Path $OutDir "cleanup.log")
    $post = Get-Fingerprint
    $post | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 (Join-Path $OutDir "fingerprint-post.json")
    $preJson = ($pre | ConvertTo-Json -Depth 6 -Compress)
    $postJson = ($post | ConvertTo-Json -Depth 6 -Compress)
    $fingerprintOk = ($preJson -eq $postJson)
    $summary = @{ batch = $batch; scenarios = $script:Results; fingerprintMatch = $fingerprintOk; docIds = $script:DocIds }
    $summary | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 (Join-Path $OutDir "summary.json")
    Write-Host ($summary | ConvertTo-Json -Depth 8)
    $anyFail = ($script:Results.Values | Where-Object { $_ -ne "PASS" }).Count -gt 0
    if ($anyFail -or -not $fingerprintOk) { exit 1 }
    exit 0
}

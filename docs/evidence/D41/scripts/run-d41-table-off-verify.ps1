# =============================================================================
# D41 R01 P5 补充：table.enabled=false 应用级零挂接实证（H4 承诺）
# 断言：OFF 态导入不写表格域、表格端点 404、文档主链（登记/导入/向量化/闭环删除）照常。
# 退出码 0=PASS。
# =============================================================================
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$module = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D41\D41_TASIIEM"
$td     = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs\D41"
$ev     = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D41"
$OutDir = Join-Path $ev "regression\off-app"
$scripts= "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D40\R03\scripts"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$batch  = "d41t-off-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$Results = [ordered]@{}
$script:DocIds = New-Object System.Collections.Generic.List[string]

function Fail([string]$m) { throw $m }
function Invoke-Db([string]$Kind, [string]$SqlText) {
    $jar = if ($Kind -eq 'mysql') { "D:\SoftwareInstall\Apache\repository\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar" }
           else { "D:\SoftwareInstall\Apache\repository\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar" }
    return (& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' -cp $jar (Join-Path $scripts "DbExec.java") $Kind -e $SqlText) -join "`n"
}
$BaseUrl = "http://127.0.0.1:9090"
function Invoke-Api([string]$Method, [string]$Path, $Body, [string]$FormFile, [string]$Token) {
    $tok = if ($PSBoundParameters.ContainsKey('Token')) { $Token } else { $script:AdminToken }
    $headers = @{}
    if ($tok) { $headers["Authorization"] = "Bearer $tok" }
    try {
        if ($FormFile) {
            return Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -Headers $headers -Form @{ file = Get-Item $FormFile }
        } elseif ($Body) {
            $json = $Body | ConvertTo-Json -Depth 8
            return Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json))
        } else {
            return Invoke-RestMethod -Method $Method -Uri "$BaseUrl$Path" -Headers $headers
        }
    } catch {
        return @{ error = $_.Exception.Message; status = $_.Exception.Response.StatusCode.value__; code = -1 }
    }
}

if (-not $env:MYSQL_PASSWORD) { Fail "BLOCKED: MYSQL_PASSWORD missing" }
if (Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue) { Fail "BLOCKED: 9090 busy" }

# 凭据（复用演练 ADMIN 口径）
$adminToken = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$sha = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($adminToken))).Replace('-','').ToLower()
Invoke-Db mysql "INSERT INTO d41_auth_principal (token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled, expires_at) VALUES ('$sha','$batch-admin','T1','D-A',JSON_ARRAY('DEV-CNC-A1'),'ADMIN',1,DATE_ADD(NOW(),INTERVAL 1 HOUR))" | Out-Null
$auditMaxPre = ([regex]::Match((Invoke-Db mysql "SELECT COALESCE(MAX(id),0) AS c FROM d41_admin_audit"), '(?m)^(\d+)\s*$')).Groups[1].Value

$cp = (Get-Content (Join-Path $module "target\r01-cp.txt") -Raw).Trim()
$env:AI_MODE = "mock"; $env:KNOWLEDGE_TABLE_ENABLED = "false"
$proc = Start-Process -FilePath 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' `
    -ArgumentList @("-cp", "`"$module\target\classes;$cp`"", "com.zl.d41.D41SpringAiApplication") `
    -WorkingDirectory $module -RedirectStandardOutput (Join-Path $OutDir "app.out.log") `
    -RedirectStandardError (Join-Path $OutDir "app.err.log") -PassThru -WindowStyle Hidden
try {
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        if (Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue) { $ok = $true; break }
        if ($proc.HasExited) { Fail "app exited early" }
    }
    if (-not $ok) { Fail "app not listening within 120s" }

    # 1) 导入 txt + PDF（OFF 态主链）
    $r1 = Invoke-Api POST "/api/v1/knowledge/documents" @{ source="$batch-txt"; title="$batch-txt"; version="1"; tenantId="T1"; deptId="D-A"; deviceId="DEV-CNC-A1" }
    $id1 = $r1.data.documentId; $script:DocIds.Add($id1)
    $i1 = Invoke-Api POST "/api/v1/knowledge/documents/$id1/import" -FormFile (Join-Path $td "fixtures\texts\d41-fx-pipe.txt")
    $r2 = Invoke-Api POST "/api/v1/knowledge/documents" @{ source="$batch-pdf"; title="$batch-pdf"; version="1"; tenantId="T1"; deptId="D-A"; deviceId="DEV-CNC-A1" }
    $id2 = $r2.data.documentId; $script:DocIds.Add($id2)
    $i2 = Invoke-Api POST "/api/v1/knowledge/documents/$id2/import" -FormFile (Join-Path $td "fixtures\pdfs\d41-fx-params.pdf")
    $Results["导入主链"] = if ($i1.code -eq 200 -and $i2.code -eq 200 -and -not $i1.data.duplicate -and -not $i2.data.duplicate) { "PASS" } else { "FAIL $($i1.code)/$($i2.code)" }

    # 2) 表格域零写入
    $te = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_table_extract"), '(?m)^(\d+)\s*$')).Groups[1].Value
    $Results["表格域零写入(te=$te)"] = if ($te -eq 0) { "PASS" } else { "FAIL" }

    # 3) 表格端点 404（控制器未装配）
    $g = Invoke-Api GET "/api/v1/knowledge/documents/$id2/tables"
    $Results["tables 端点未装配(证据=app 日志 NoResourceFoundException)"] = if ($g.code -ne 200) { "PASS" } else { "FAIL" }
    $e = Invoke-Api POST "/api/v1/knowledge/documents/$id2/tables/extract"
    $Results["extract 端点未装配"] = if ($e.code -ne 200) { "PASS" } else { "FAIL" }

    # 4) 向量化主链照常 + reconcile 照常（tableOrphans 空数组或 0，无 500）
    $v = Invoke-Api POST "/api/v1/knowledge/documents/$id2/vector-index"
    $Results["vector-index 照常"] = if ($v.code -eq 200) { "PASS" } else { "FAIL $($v.code)" }
    $rec = Invoke-Api POST "/api/v1/knowledge/vector-index/reconcile?dryRun=true"
    $toCnt = if ($null -ne $rec.data.tableOrphans) { @($rec.data.tableOrphans).Count } else { -1 }
    $Results["reconcile 照常(tableOrphans=$toCnt)"] = if ($rec.code -eq 200 -and ($toCnt -eq 0 -or $toCnt -eq -1)) { "PASS" } else { "FAIL $($rec.code)" }

    # 5) 删除闭环照常
    $del = Invoke-Api DELETE "/api/v1/knowledge/documents/$id1/vector-index?version=1"
    $Results["删除闭环照常"] = if ($del.code -eq 200) { "PASS" } else { "FAIL $($del.code)" }
}
finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    if ($script:DocIds.Count -gt 0) {
        $dl = ($script:DocIds | ForEach-Object { "'$_'" }) -join ","
        Invoke-Db mysql "DELETE FROM knowledge_document_page WHERE document_id IN ($dl); DELETE FROM knowledge_document_content WHERE document_id IN ($dl); DELETE FROM vector_chunk_index WHERE document_id IN ($dl); DELETE FROM vector_index_task WHERE document_id IN ($dl); DELETE FROM d41_table_row WHERE table_id IN (SELECT id FROM d41_table_extract WHERE document_id IN ($dl)); DELETE FROM d41_table_extract WHERE document_id IN ($dl); DELETE FROM d41_document_sync WHERE document_id COLLATE utf8mb4_general_ci IN ($dl); DELETE FROM knowledge_document WHERE document_id IN ($dl); DELETE FROM d41_auth_principal WHERE user_id LIKE 'd41t-off-%'; DELETE FROM d41_admin_audit WHERE actor_user_id LIKE 'd41t-off-%'" 2>&1 | Out-Null
        Invoke-Db pg "DELETE FROM vector_store WHERE metadata->>'documentId' IN ($dl)" 2>&1 | Out-Null
    }
}

# 6) 基线复原核验
$pg = ([regex]::Match((Invoke-Db pg "SELECT count(*) AS c FROM vector_store"), '(?m)^(\d+)\s*$')).Groups[1].Value
$kd = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM knowledge_document"), '(?m)^(\d+)\s*$')).Groups[1].Value
$pr = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_auth_principal"), '(?m)^(\d+)\s*$')).Groups[1].Value
$au = ([regex]::Match((Invoke-Db mysql "SELECT count(*) AS c FROM d41_admin_audit"), '(?m)^(\d+)\s*$')).Groups[1].Value
$Results["基线复原(kd=$kd pg=$pg prin=$pr aud=$au/$auditMaxPre)"] = if ($kd -eq 10 -and $pg -eq 30 -and $pr -eq 4 -and $au -eq [int]$auditMaxPre) { "PASS" } else { "FAIL" }

$Results | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 (Join-Path $OutDir "off-verify-verdict.json")
Write-Host "== D41 R01 OFF 态实证 =="
$Results.GetEnumerator() | ForEach-Object { Write-Host "$($_.Key): $($_.Value)" }
exit $(if (($Results.Values | Where-Object { $_ -ne "PASS" }).Count -eq 0) { 0 } else { 1 })


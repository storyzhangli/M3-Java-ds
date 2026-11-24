param([string]$Phase = 'PRE', [string]$OutDir = 'E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D41\regression')
[Console]::OutputEncoding=[Text.Encoding]::UTF8
$r="E:\AI\project\AI-Learning-two-lp\M3-Java-ds"
$dbexec="$r\docs\evidence\D40\R03\scripts\DbExec.java"
$repo='D:\SoftwareInstall\Apache\repository'
# MYSQL_PASSWORD 由调用方环境注入（H9：脚本内禁止硬编码凭据）
if (-not $env:MYSQL_PASSWORD) { 'FINGERPRINT_ERROR: MYSQL_PASSWORD env missing'; exit 2 }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$fp=[ordered]@{}
# MySQL counts
$sql="SELECT 'knowledge_document' t, count(*) c FROM knowledge_document UNION ALL SELECT 'knowledge_document_page', count(*) FROM knowledge_document_page UNION ALL SELECT 'knowledge_document_content', count(*) FROM knowledge_document_content UNION ALL SELECT 'vector_chunk_index', count(*) FROM vector_chunk_index UNION ALL SELECT 'vector_index_task', count(*) FROM vector_index_task UNION ALL SELECT 'd41_document_sync', count(*) FROM d41_document_sync UNION ALL SELECT 'd41_admin_audit', count(*) FROM d41_admin_audit UNION ALL SELECT 'd41_auth_principal', count(*) FROM d41_auth_principal UNION ALL SELECT 'd41_table_extract', count(*) FROM d41_table_extract UNION ALL SELECT 'd41_table_row', count(*) FROM d41_table_row;"
$out = & 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dstdout.encoding=UTF-8' -cp "$repo\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar" $dbexec mysql -e $sql 2>&1
$out | ForEach-Object { if ($_ -match '^(knowledge_document_page|knowledge_document_content|knowledge_document|vector_chunk_index|vector_index_task|d41_\w+)\s+(\d+)$') { $fp[$matches[1]] = [int]$matches[2] } }
# PG counts
$sql2="SELECT 'vector_store' t, count(*) c FROM vector_store UNION ALL SELECT 'two_vector_store', count(*) FROM two_vector_store;"
$out2 = & 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dstdout.encoding=UTF-8' -cp "$repo\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar" $dbexec pg -e $sql2 2>&1
$out2 | ForEach-Object { if ($_ -match '^(vector_store|two_vector_store)\s+(\d+)$') { $fp[$matches[1]] = [int]$matches[2] } }
# fixture residual counts (d41- prefix)
$sql3="SELECT 'kd_d41fix' t, count(*) c FROM knowledge_document WHERE document_id LIKE 'd41-%' UNION ALL SELECT 'te_d41fix', count(*) FROM d41_table_extract WHERE document_id LIKE 'd41-%' UNION ALL SELECT 'sync_d41fix', count(*) FROM d41_document_sync WHERE document_id LIKE 'd41-%' UNION ALL SELECT 'chunk_d41fix', count(*) FROM vector_chunk_index WHERE document_id LIKE 'd41-%' UNION ALL SELECT 'd41te_total', count(*) FROM d41_table_extract UNION ALL SELECT 'd41tr_total', count(*) FROM d41_table_row;"
$out3 = & 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dstdout.encoding=UTF-8' -cp "$repo\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar" $dbexec mysql -e $sql3 2>&1
$fx=[ordered]@{}
$out3 | ForEach-Object { if ($_ -match '^(kd_d41fix|te_d41fix|sync_d41fix|chunk_d41fix|d41te_total|d41tr_total)\s+(\d+)$') { $fx[$matches[1]] = [int]$matches[2] } }
# PG d41- residual via metadata documentId
$sql4="SELECT 'pg_vs_d41fix' t, count(*) c FROM vector_store WHERE metadata->>'documentId' LIKE 'd41-%' UNION ALL SELECT 'pg_two_d41fix', count(*) FROM two_vector_store WHERE metadata->>'documentId' LIKE 'd41-%';"
$out4 = & 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dstdout.encoding=UTF-8' -cp "$repo\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar" $dbexec pg -e $sql4 2>&1
$out4 | ForEach-Object { if ($_ -match '^(pg_vs_d41fix|pg_two_d41fix)\s+(\d+)$') { $fx[$matches[1]] = [int]$matches[2] } }
$doc = [ordered]@{ phase=$Phase; at=(Get-Date -Format 'o'); counts=$fp; fixtureResidual=$fx; legacyCheck=@{ vector_store=30; two_vector_store=8 } }
$json = $doc | ConvertTo-Json -Depth 5
Set-Content -Path "$OutDir\fingerprint-$Phase.json" -Value $json -Encoding utf8
"PHASE=$Phase counts=" + ($fp.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
"residual=" + (($fx.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')
if ($fp['vector_store'] -ne 30 -or $fp['two_vector_store'] -ne 8) { 'FINGERPRINT_ERROR: legacy drift'; exit 2 }
# 硬门禁=表格域零行（d41_table_* 是 D41 R01 新概念，初始必须 0）；
# kd/sync/chunk 的 d41- 前缀计数为外部并发观察项（2026-11-24 发现 D41-R03VERIFY-* 自测资产，
# 本批 documentId 为 UUID 不入该 LIKE 域），PRE/POST 记录基线不判错，状态文件说明。
if (($fx['d41te_total'] + $fx['d41tr_total']) -ne 0) { 'ERROR: d41_table domain not empty'; exit 2 }
if ($Phase -eq 'PRE' -and ($fx['te_d41fix'] + $fx['chunk_d41fix'] + $fx['pg_vs_d41fix'] + $fx['pg_two_d41fix']) -ne 0) { 'PRE_ERROR: d41 fixture residual'; exit 2 }
'FINGERPRINT_OK'

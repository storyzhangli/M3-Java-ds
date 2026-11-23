# =============================================================================
# D40 R01 执行层验证脚本：DDL + d40t- 夹具 + E1–E5 异常矩阵 + 指纹五元组
# 状态：未执行（编写于 2026-11-23，MySQL 直连通道被风险确认弹窗阻塞，见 OPEN 项）
# 前置：1) 用户已解锁本机 MySQL 执行通道；2) $env:MYSQL_PASSWORD / PG_VECTOR_PASSWORD
#          按既有规范仅经环境变量注入，禁止硬编码与日志输出。
# 约定：全部夹具 d40t- 前缀；two 轨关闭；AI_MODE=mock；结束必须清理并回归指纹。
# =============================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:9090",
    [string]$MysqlHost = "127.0.0.1:3306/ai_db"
)
$ErrorActionPreference = "Stop"
$ev = Join-Path $PSScriptRoot ".."
$out = Join-Path $ev ("D40\execution\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $out | Out-Null

# ---------- 步骤 0：DDL（幂等，仅建缺失表；来源 sql/d40/d40_document_sync.sql、d40_admin_audit.sql） ----------
$mysql = "mysql -h 127.0.0.1 -P 3306 -u root ai_db"
Write-Host "[0] 应用 D40 DDL（幂等 CREATE TABLE IF NOT EXISTS）" 
# 实际执行时经 JDBC/命令行两次源文件顺序应用，随后核验：
#   SHOW TABLES LIKE 'd40_document_sync'; SHOW TABLES LIKE 'd40_admin_audit';

# ---------- 步骤 1：指纹五元组 PRE ----------
# payloadHash + 排序 chunkId 集合 + PG count + 关联 count + INDEXED count
# 记录到 $out/fingerprint-pre.json（沿用 KP2 盘点 SQL 与 /corpus/index 只读通道）

# ---------- 步骤 2：d40t- 夹具（user_id 维度增量，绝不触碰预存 4 principal / 9 d40-doc-*） ----------
# 启动参数：AI_MODE=mock APP_ACCESS_ENABLED=true D40_SYNC_RETRY_ENABLED=true
#           D40_FAULT_INJECTION_ENABLED=true（E1/E3 演练窗口内）
# 夹具文档：d40t-e1-doc / d40t-e2-doc / d40t-e3-doc / d40t-e4-doc / d40t-e5-doc
# 凭据：临时 token 仅写入 docs\temp_docs\D40\（禁提交），头部 X-Auth-Token 使用。

# ---------- 步骤 3：E1 删除-向量端失败（故障注入装饰器） ----------
# 启动带 -Dapp.vector-index.fault-injection.fail-delete-call=1
# 调 DELETE /api/v1/knowledge/vectors/{d40t-e1-doc} → 预期：
#   d40_document_sync(d40t-e1-doc, DELETE) = FAILED / PG_UNREACHABLE / attempt=1
# 证据：响应体 + sync 行快照 → $out/e1.json

# ---------- 步骤 4：E1-R 重试收敛（修复走管线，禁直改 SQL） ----------
# 重启去掉注入（或 call 阈值移过），POST /api/v1/d40/sync/retry {documentId:d40t-e1-doc, operation:DELETE}
# 预期：SYNCED + reason 清空 + PG count(d40t-e1-doc)=0 + 关联 count=0（双端确认）
# 证据：$out/e1r.json

# ---------- 步骤 5：E2 删除-MySQL 关联清理失败 ----------
# 关联删除失败路径（可经临时坏 strategy 参数/约束演练或注入 add-call）→
# 预期：FAILED / CONFIRM_MISMATCH 或 PG_DELETE_FAILED 归因；重试收敛 SYNCED。

# ---------- 步骤 6：E3 清理钩子失败（UPDATE_CLEANUP） ----------
# 旧版本清理钩子失败 → d40_document_sync(DELETE?  UPDATE_CLEANUP, version) FAILED 落库；
# 重试端点重放 cleanupSupersededVersion 管线收敛。

# ---------- 步骤 7：E4 SYNC_STUCK ----------
# 用短 stuck-minutes（-Dapp.vector-index.stuck-minutes=1）+ 注入使 FAILED 到期不收敛；
# POST /api/v1/knowledge/vectors/reconcile?dryRun=true →
# 预期：syncStuck 清单含 d40t-e4-doc（DRIFT_SYNC_STUCK 分类）；actionsTaken 无直改 SQL 项；
# 关闭注入后重试管线自然收敛，再跑 reconcile → syncStuck 清空。

# ---------- 步骤 8：E5 版本指针漂移（SWITCH 事务权威性） ----------
# 对 d40t-e5-doc 连续导入 v1→v2（触发 SWITCH：version 指针与任务状态同事务）；
# 预期：reconcile dryRun 无 POINTER/MULTI-COMPLETED 漂移；
# 对照组：只读核验 knowledge_document.version == 唯一 COMPLETED 任务 version。
# 证据：$out/e5.json（漂移清单应为空 + 任务状态快照）

# ---------- 步骤 9：审计核验（access=true 窗口） ----------
# SELECT action,result,COUNT(*) FROM d40_admin_audit
#  WHERE target_document_id LIKE 'd40t-%' GROUP BY action,result;
# 预期：E1–E5 涉及的特权动作均有 ALLOWED 行；DENIED 由 4001 链日志覆盖（矩阵口径）。

# ---------- 步骤 10：清理 + 指纹 POST 回归 ----------
# 删除全部 d40t- 夹具（走正式删除闭环管线，非直改 SQL）；
# 指纹五元组 POST 必须与 PRE 逐字节一致（payloadHash/chunkId 集合/PG count/关联 count/INDEXED count）；
# 核验 d40_document_sync/d40_admin_audit/vector_store/two_vector_store 无 d40t- 残留：
#   SELECT COUNT(*) FROM d40_document_sync WHERE document_id LIKE 'd40t-%';  -- 应为 0
# 结论写入 $out/verdict.json；two 轨行数保持 8 不变。

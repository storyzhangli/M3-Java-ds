-- D40 R03-05 结构对齐 ALTER（仅安全变更：两表执行前复核 0 行；不 DROP 表、不 TRUNCATE、不删数据）
-- 目标结构 = docs/evidence/D40/ddl/d40-sync-ddl.sql（R01 冻结 DDL）
-- 背景：库中现存旧版结构（version INT / DATETIME 无微秒 / 索引名不一致），与 Repository 字符串 version 口径不兼容。

-- 安全门：执行前必须 0 行（由调用脚本核验，非 0 即中止）
SELECT COUNT(*) AS sync_rows_before FROM d40_document_sync;
SELECT COUNT(*) AS audit_rows_before FROM d40_admin_audit;

ALTER TABLE d40_document_sync
  MODIFY COLUMN document_id VARCHAR(128) NOT NULL COMMENT '文档业务标识',
  MODIFY COLUMN version VARCHAR(32) NOT NULL DEFAULT '' COMMENT '文档版本；空串=文档级操作',
  MODIFY COLUMN sync_status ENUM('PENDING','SYNCED','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '操作级同步状态',
  MODIFY COLUMN attempt_count INT NOT NULL DEFAULT 0 COMMENT '失败重试计数（手动 retry 对耗尽行重置）',
  MODIFY COLUMN next_retry_at DATETIME(6) NULL COMMENT 'FAILED=下次自动重试时间；PENDING=执行租约到期时间',
  MODIFY COLUMN payload_hash VARCHAR(64) NOT NULL DEFAULT '' COMMENT '载荷指纹（排序 chunkId SHA-256）',
  MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  MODIFY COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  RENAME INDEX idx_sync_status_retry TO idx_status_retry,
  COMMENT = 'D40 操作级同步状态机（与 vector_index_task 任务级状态机正交共存）';

ALTER TABLE d40_admin_audit
  MODIFY COLUMN actor_user_id VARCHAR(128) NOT NULL COMMENT '服务端可信身份 userId',
  MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '可信租户',
  MODIFY COLUMN role VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'USER/ADMIN',
  MODIFY COLUMN target_document_id VARCHAR(128) NOT NULL DEFAULT '' COMMENT '目标文档',
  MODIFY COLUMN version VARCHAR(32) NOT NULL DEFAULT '' COMMENT '目标版本或模式标记',
  MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT 'MDC traceId 优先，缺失 UUID',
  MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  RENAME INDEX idx_audit_actor_time TO idx_actor_time,
  DROP INDEX idx_audit_action,
  ADD INDEX idx_action_target (action, target_document_id),
  COMMENT = 'D40 特权写面审计留痕（仅 access.enabled=true 装配写入）';

-- 执行后复核
SELECT COUNT(*) AS sync_rows_after FROM d40_document_sync;
SELECT COUNT(*) AS audit_rows_after FROM d40_admin_audit;
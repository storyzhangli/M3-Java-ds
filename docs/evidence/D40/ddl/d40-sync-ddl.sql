-- D40 R01 同步闭环 DDL（MySQL ai_db；幂等：CREATE TABLE IF NOT EXISTS，双跑零报错零变化）
-- 注意：version 以空串参与唯一键（MySQL uk 对 NULL 不去重，sync-params.json KP4 裁决）。
-- 跨仓共享提醒：表名 d40_ 前缀为任务书冻结（H6/H9）；数据行前缀 d40t- 防撞 one 仓 d40- 夹具。

CREATE TABLE IF NOT EXISTS d40_document_sync (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    document_id   VARCHAR(128)    NOT NULL COMMENT '文档业务标识',
    operation     ENUM('DELETE','UPDATE_CLEANUP') NOT NULL COMMENT '同步操作类型',
    version       VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '文档版本；空串=文档级操作',
    sync_status   ENUM('PENDING','SYNCED','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '操作级同步状态',
    reason_code   VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '细分原因码；RETRY_EXHAUSTED=耗尽待人工',
    attempt_count INT             NOT NULL DEFAULT 0 COMMENT '失败重试计数（手动 retry 对耗尽行重置）',
    next_retry_at DATETIME(6)     NULL COMMENT '自动重试到期时间；退避=retryBackoffMs*2^(attempt-1) 上限300s',
    payload_hash  VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '载荷指纹（排序 chunkId SHA-256）',
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_op_version (document_id, operation, version),
    KEY idx_status_retry (sync_status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D40 操作级同步状态机（与 vector_index_task 任务级状态机正交共存）';

CREATE TABLE IF NOT EXISTS d40_admin_audit (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    actor_user_id      VARCHAR(128)    NOT NULL COMMENT '服务端可信身份 userId',
    tenant_id          VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '可信租户',
    role               VARCHAR(16)     NOT NULL DEFAULT '' COMMENT 'USER/ADMIN',
    action             VARCHAR(64)     NOT NULL COMMENT '操作标识（VECTOR_INDEX/SYNC_RETRY/...）',
    target_document_id VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '目标文档',
    version            VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '目标版本或模式标记',
    result             VARCHAR(16)     NOT NULL COMMENT 'ALLOWED/FAILED（拒绝由 4001 异常链留痕，KP1 裁决）',
    reason_code        VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '失败原因码',
    request_id         VARCHAR(64)     NOT NULL COMMENT 'MDC traceId 优先，缺失 UUID',
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_actor_time (actor_user_id, created_at),
    KEY idx_action_target (action, target_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='D40 特权写面审计留痕（仅 access.enabled=true 装配写入）';

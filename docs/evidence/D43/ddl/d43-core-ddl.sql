-- D43 R01 三表幂等 DDL（模板：D40 sync-ddl + D39 access-ddl，前缀 d40_/d39_ 机械替换为 d43_，列结构零变化）
-- 背景：D43 骨架代码 SQL 已 d43_ 化（F09/F10），但 d43_* 表在库缺失（KP2 实测）；本轮按 KP2 裁决建表。
CREATE TABLE IF NOT EXISTS d43_document_sync (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    document_id   VARCHAR(128)    NOT NULL COMMENT '文档业务标识',
    operation     ENUM('DELETE','UPDATE_CLEANUP') NOT NULL COMMENT '同步操作类型',
    version       VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '文档版本；空串=文档级操作',
    sync_status   ENUM('PENDING','SYNCED','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '操作级同步状态',
    reason_code   VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '细分原因码',
    attempt_count INT             NOT NULL DEFAULT 0 COMMENT '失败重试计数',
    next_retry_at DATETIME(6)     NULL COMMENT '自动重试到期时间',
    payload_hash  VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '载荷指纹',
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_op_version (document_id, operation, version),
    KEY idx_status_retry (sync_status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='D43 操作级同步状态机';
CREATE TABLE IF NOT EXISTS d43_admin_audit (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    actor_user_id      VARCHAR(128)    NOT NULL COMMENT '服务端可信身份 userId',
    tenant_id          VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '可信租户',
    role               VARCHAR(16)     NOT NULL DEFAULT '' COMMENT 'USER/ADMIN',
    action             VARCHAR(64)     NOT NULL COMMENT '操作标识',
    target_document_id VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '目标文档',
    version            VARCHAR(32)     NOT NULL DEFAULT '' COMMENT '目标版本或模式标记',
    result             VARCHAR(16)     NOT NULL COMMENT 'ALLOWED/FAILED',
    reason_code        VARCHAR(64)     NOT NULL DEFAULT '' COMMENT '失败原因码',
    request_id         VARCHAR(64)     NOT NULL COMMENT 'traceId',
    created_at         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_actor_time (actor_user_id, created_at),
    KEY idx_action_target (action, target_document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='D43 特权写面审计留痕';
CREATE TABLE IF NOT EXISTS d43_auth_principal (
  token_sha256 CHAR(64) NOT NULL COMMENT 'Bearer token SHA-256 小写 hex',
  user_id VARCHAR(64) NOT NULL COMMENT '业务用户',
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户（硬隔离边界）',
  dept_id VARCHAR(64) NOT NULL COMMENT '部门',
  device_scopes JSON NOT NULL COMMENT '可见设备数组',
  role ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '管理员判定来源',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '禁用位',
  expires_at DATETIME DEFAULT NULL COMMENT '过期时刻；NULL=不过期',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token_sha256),
  KEY idx_principal_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='D43 服务端可信身份';
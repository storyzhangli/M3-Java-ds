-- D39 R01 权限体系幂等 DDL（MySQL ai_db）。×2 重跑零报错零数据变化（C03）。
-- 说明：knowledge_document 三权限列在执行时已存在（P0 漂移取证见 probe/access-params.json driftNote），
-- 本文件用 information_schema 守卫 + 动态 SQL 实现“不存在才添加”的幂等语义（MySQL 8 无 ADD COLUMN IF NOT EXISTS）。

CREATE TABLE IF NOT EXISTS d39_auth_principal (
  token_sha256 CHAR(64) NOT NULL COMMENT 'Bearer token SHA-256 小写 hex；DB 不存明文（H9）',
  user_id VARCHAR(64) NOT NULL COMMENT '业务用户；enabled 时兼会话 owner（D6）',
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户（硬隔离边界，H7）',
  dept_id VARCHAR(64) NOT NULL COMMENT '部门；ADMIN 在租户内 bypass 用哨兵显式值',
  device_scopes JSON NOT NULL COMMENT '可见设备数组；[]=无专属设备仅哨兵文档',
  role ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '唯一管理员判定来源（H3）',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '禁用位：0=fail-closed 拒绝',
  expires_at DATETIME DEFAULT NULL COMMENT '过期时刻；NULL=不过期',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (token_sha256),
  KEY idx_principal_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_document' AND column_name='tenant_id')=0,
  'ALTER TABLE knowledge_document ADD COLUMN tenant_id VARCHAR(64) NULL', 'SELECT ''tenant_id exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_document' AND column_name='dept_id')=0,
  'ALTER TABLE knowledge_document ADD COLUMN dept_id VARCHAR(64) NULL', 'SELECT ''dept_id exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='knowledge_document' AND column_name='device_id')=0,
  'ALTER TABLE knowledge_document ADD COLUMN device_id VARCHAR(64) NULL', 'SELECT ''device_id exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

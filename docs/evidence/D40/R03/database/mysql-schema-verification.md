# D40 R03-05 MySQL 结构核验报告

- 核验时间：2026-11-23（本地 UTC+8）
- 数据库：`127.0.0.1:3306/ai_db`（MySQL 8，本地开发库）
- 目标表：`d40_document_sync`、`d40_admin_audit`
- 对照基准：`docs/evidence/D40/ddl/d40-sync-ddl.sql`（R01 冻结 DDL）+ Repository 参数化 SQL 口径

## 1. 执行前发现（结构不兼容）

两表已存在但为**旧版结构**，与当前 Repository SQL 不兼容：

| 项 | 库中旧结构 | 冻结 DDL / Repository 口径 | 影响 |
| --- | --- | --- | --- |
| d40_document_sync.version | INT NOT NULL DEFAULT 0 | VARCHAR(32) DEFAULT ''（空串参与自然键） | 文档级删除 version="" 无法写入；字符串参数与 INT 列冲突 |
| d40_admin_audit.version | INT | VARCHAR(32) | 同上（AuditService 写字符串） |
| next_retry_at / created_at / updated_at | DATETIME（秒级） | DATETIME(6)（微秒） | CAS 租约等值比较精度风险 |
| document_id / actor_user_id / target_document_id | VARCHAR(64) | VARCHAR(128) | 长 ID 截断风险 |
| payload_hash | CHAR(64) | VARCHAR(64) | 兼容但口径不一致 |
| 索引名 | idx_sync_status_retry / idx_audit_actor_time / idx_audit_action(action) | idx_status_retry / idx_actor_time / idx_action_target(action,target_document_id) | 审计按目标查询无复合索引 |

两表执行前均 **0 行**（AUTO_INCREMENT=45/8 为历史测试残留计数，无现存数据）。

## 2. 应用的安全变更

脚本：`mysql-ddl-align.sql`；执行日志：`mysql-ddl-apply.log`（6 语句 0 失败）。

- 仅 ALTER MODIFY / RENAME INDEX / DROP+ADD 二级索引；**未 DROP 表、未 TRUNCATE、未删除任何数据行**。
- 执行前后 COUNT 复核均 0 行；AUTO_INCREMENT 保留。
- 中文 COMMENT 经 UTF-8 连接写入，SHOW CREATE TABLE 回读正常。

## 3. 执行后核验（真实 SQL 输出）

- `mysql-show-create-table.txt`：两表最终 CREATE TABLE（version VARCHAR(32)、DATETIME(6)、索引名与冻结 DDL 一致）。
- `mysql-show-index.txt`：
  - d40_document_sync：PRIMARY(id)、UNIQUE uk_doc_op_version(document_id,operation,version)、idx_status_retry(sync_status,next_retry_at)。
  - d40_admin_audit：PRIMARY(id)、idx_actor_time(actor_user_id,created_at)、idx_action_target(action,target_document_id)。
- 旧结构留档：`mysql-show-create-table-before.txt`、`mysql-show-index-before.txt`。

## 4. Repository SQL 兼容性结论

- DocumentSyncRepository：INSERT 10 列 / UPDATE 状态字段 / claimForRetry（`next_retry_at <=> ?`）/ markPendingConditional / findDueForRetry 均与最终结构列名、类型、可空性一致。
- AdminAuditRepository：INSERT 10 列字符串 version 与 VARCHAR(32) 一致。
- 自然键 (document_id, operation, version) 唯一约束在位，version 空串语义可表达。

**结果：PASS（真实部署核验完成，非模拟）。**
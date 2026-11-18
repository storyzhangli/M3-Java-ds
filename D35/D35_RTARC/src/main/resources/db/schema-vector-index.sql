-- ============================================================================
-- D31 向量入库、更新与幂等：MySQL 状态表 + Chunk 关联表 DDL
-- 数据库：ai_db（MySQL 8+，InnoDB，utf8mb4）
-- 本脚本幂等（CREATE TABLE IF NOT EXISTS），可安全重复执行；不删除任何已有表与数据。
--
-- 职责边界：
--   vector_index_task  文档版本级索引状态机（PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED），
--                      兼作轻量 Outbox 任务行：记录阶段、错误、尝试次数与下次重试时间；
--   vector_chunk_index Chunk 级稳定关联：稳定 chunkId（UUID v5）、顺序、内容指纹与文本快照，
--                      用于按文档查询/删除、孤儿核对与缺失重建。
--
-- 与 Spring AI PgVectorStore 的关系：
--   向量本体（embedding vector(384)）由框架管理的 PostgreSQL 表 public.vector_store 承载；
--   本脚本两张表只保存业务关联与状态，不复制向量，不修改框架表结构。
--   PgVectorStore 1.1.8 的 add() 为 INSERT ... ON CONFLICT (id) DO UPDATE（主键级 Upsert），
--   因此重复写入取决于本表给出的稳定 chunkId。
--
-- 回滚（仅限确认不再需要时）：
--   DROP TABLE IF EXISTS `vector_chunk_index`, `vector_index_task`;
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 文档版本级索引状态表：一个 documentId + version 一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `vector_index_task` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`       VARCHAR(64)  NOT NULL COMMENT '文档业务标识',
    `version`           VARCHAR(64)  NOT NULL COMMENT '文档版本（业务给定）',
    `strategy`          VARCHAR(32)  NOT NULL COMMENT '切分策略 FIXED_LENGTH/STRUCTURE_AWARE',
    `content_hash`      CHAR(64)     NOT NULL COMMENT '索引载荷指纹 SHA-256（chunkIndex:content 拼接）',
    `split_version`     VARCHAR(64)  NOT NULL COMMENT '切分算法版本（参与稳定 ID）',
    `vector_space_id`   VARCHAR(128) NOT NULL COMMENT '向量空间标识（模型+版本+维度，参与稳定 ID）',
    `chunk_count`       INT          NOT NULL DEFAULT 0 COMMENT '该版本 Chunk 总数',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                        COMMENT 'PENDING/INDEXING/COMPLETED/PARTIAL/FAILED/SUPERSEDED',
    `stage`             VARCHAR(24)  NOT NULL DEFAULT ''
                        COMMENT '最后进入阶段：REGISTER/EMBED_WRITE/VERIFY/SWITCH/CLEANUP',
    `error_code`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '失败错误码（不含堆栈与凭据）',
    `error_message`     VARCHAR(500) NOT NULL DEFAULT '' COMMENT '安全错误摘要',
    `attempt_count`     INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（含首次）',
    `next_retry_at`     DATETIME(6)  NULL COMMENT '下次计划重试时间，NULL 表示不等待',
    `created_at`        DATETIME(6)  NOT NULL COMMENT '创建时间',
    `updated_at`        DATETIME(6)  NOT NULL COMMENT '状态更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vector_task_doc_version` (`document_id`, `version`),
    KEY `idx_vector_task_status_retry` (`status`, `next_retry_at`),
    KEY `idx_vector_task_status_updated` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D31 文档向量索引状态表（状态机与轻量 Outbox）';

-- ---------------------------------------------------------------------------
-- Chunk 级关联表：一个稳定 chunkId 一行
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `vector_chunk_index` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    `document_id`        VARCHAR(64) NOT NULL COMMENT '所属文档业务标识',
    `version`            VARCHAR(64) NOT NULL COMMENT '所属文档版本',
    `strategy`           VARCHAR(32) NOT NULL COMMENT '切分策略',
    `chunk_id`           CHAR(36)    NOT NULL COMMENT '稳定 Chunk 标识（UUID v5，36 字符含横线）',
    `chunk_index`        INT         NOT NULL COMMENT '版本与策略内的 Chunk 顺序，从 0 开始',
    `content`            MEDIUMTEXT  NOT NULL COMMENT 'Chunk 文本快照（Outbox 载荷，供缺失重建）',
    `chunk_content_hash` CHAR(64)    NOT NULL COMMENT 'Chunk 文本内容指纹 SHA-256',
    `character_count`    INT         NOT NULL COMMENT 'Chunk 字符长度（Unicode 码点）',
    `page_numbers`       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '来源页码，逗号分隔（可空串）',
    `status`             VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '快照状态 PENDING/INDEXED/FAILED',
    `created_at`         DATETIME(6) NOT NULL COMMENT '创建时间',
    `updated_at`         DATETIME(6) NOT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_vector_chunk_chunk_id` (`chunk_id`),
    UNIQUE KEY `uk_vector_chunk_doc_version_index` (`document_id`, `version`, `chunk_index`),
    KEY `idx_vector_chunk_document` (`document_id`, `version`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D31 Chunk 稳定关联表（幂等键与孤儿核对）';

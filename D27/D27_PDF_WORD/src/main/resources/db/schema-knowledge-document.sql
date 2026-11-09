-- D26 knowledge document metadata/content tables.
-- Safe to re-run: only CREATE TABLE IF NOT EXISTS; no data deletion.

CREATE TABLE IF NOT EXISTS `knowledge_document` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `document_id`    VARCHAR(64)  NOT NULL,
    `source_system`  VARCHAR(128) NOT NULL,
    `title`          VARCHAR(300) NOT NULL,
    `version`        VARCHAR(64)  NOT NULL,
    `status`         VARCHAR(16)  NOT NULL,
    `file_name`      VARCHAR(255) NULL,
    `content_type`   VARCHAR(128) NULL,
    `file_size`      BIGINT       NULL,
    `content_hash`   CHAR(64)     NULL,
    `error_code`     VARCHAR(64)  NOT NULL DEFAULT '',
    `error_message`  VARCHAR(500) NOT NULL DEFAULT '',
    `created_at`     DATETIME(6)  NOT NULL,
    `updated_at`     DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_document_id` (`document_id`),
    UNIQUE KEY `uk_knowledge_content_hash` (`content_hash`),
    KEY `idx_knowledge_doc_status` (`status`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D26 知识文档元数据与导入状态';

CREATE TABLE IF NOT EXISTS `knowledge_document_content` (
    `document_id` VARCHAR(64) NOT NULL,
    `content`     LONGBLOB    NOT NULL,
    `parsed_text` LONGTEXT    NULL,
    `updated_at`  DATETIME(6) NOT NULL,
    PRIMARY KEY (`document_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D26 知识文档原始文件与基础解析文本';

-- Preserve business-state timestamp precision when this script is applied to
-- an earlier local D26 table created with default DATETIME precision.
ALTER TABLE `knowledge_document`
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL,
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL;
ALTER TABLE `knowledge_document_content`
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL;

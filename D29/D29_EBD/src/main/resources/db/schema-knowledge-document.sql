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

-- D27：原始文件与 PDF 页面级文本分表保存。
-- page_number 是 PDF 物理页序号（从 1 开始），不是印刷页码。
CREATE TABLE IF NOT EXISTS `knowledge_document_page` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `document_id`     VARCHAR(64)  NOT NULL,
    `page_number`     INT          NOT NULL,
    `content`         LONGTEXT     NOT NULL,
    `character_count` INT          NOT NULL,
    `blank_page`      TINYINT(1)   NOT NULL,
    `parser_name`     VARCHAR(64)  NOT NULL,
    `parser_version`  VARCHAR(32)  NOT NULL,
    `updated_at`      DATETIME(6)  NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_document_page` (`document_id`, `page_number`),
    KEY `idx_knowledge_document_page_doc` (`document_id`, `page_number`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'D27 PDF 物理页面文本与来源定位';

-- Preserve business-state timestamp precision when this script is applied to
-- an earlier local D26 table created with default DATETIME precision.
ALTER TABLE `knowledge_document`
    MODIFY COLUMN `created_at` DATETIME(6) NOT NULL,
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL;
ALTER TABLE `knowledge_document_content`
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL;
ALTER TABLE `knowledge_document_page`
    MODIFY COLUMN `updated_at` DATETIME(6) NOT NULL;

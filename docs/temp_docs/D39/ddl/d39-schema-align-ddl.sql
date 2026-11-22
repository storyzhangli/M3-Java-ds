-- D39-R01 schema 对齐 DDL（漂移修复：实库 knowledge 系表为旧版列名，与全仓 14 个模块
-- schema-knowledge-document.sql / schema 代码契约不一致，导致 register/import 链 5000）。
-- 纪律：纯追加（ADD COLUMN），不改名、不删除、不回填、不触碰任何既有列与既有行；
-- 方向 = 将实库对齐到 git 受控代码契约（PO 驼峰↔下划线自动映射所需列）。
-- 幂等：information_schema 条件 + PREPARE/EXECUTE（ExecuteTwoDdl 按行分号切语句，PREPARE 三件套逐行书写）。
-- 本脚本可安全重复执行（重复执行输出 skip）。

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'source_system');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document ADD COLUMN source_system VARCHAR(128) NOT NULL DEFAULT '''' AFTER document_id', 'SELECT ''skip: source_system exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'file_name');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document ADD COLUMN file_name VARCHAR(255) NULL AFTER status', 'SELECT ''skip: file_name exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'content_type');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document ADD COLUMN content_type VARCHAR(128) NULL AFTER file_name', 'SELECT ''skip: content_type exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document' AND COLUMN_NAME = 'file_size');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document ADD COLUMN file_size BIGINT NULL AFTER content_type', 'SELECT ''skip: file_size exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document_page' AND COLUMN_NAME = 'character_count');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document_page ADD COLUMN character_count INT NOT NULL DEFAULT 0', 'SELECT ''skip: character_count exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document_page' AND COLUMN_NAME = 'blank_page');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document_page ADD COLUMN blank_page TINYINT(1) NOT NULL DEFAULT 0', 'SELECT ''skip: blank_page exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document_page' AND COLUMN_NAME = 'parser_name');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document_page ADD COLUMN parser_name VARCHAR(64) NOT NULL DEFAULT ''''', 'SELECT ''skip: parser_name exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'knowledge_document_page' AND COLUMN_NAME = 'parser_version');
SET @sql = IF(@c = 0, 'ALTER TABLE knowledge_document_page ADD COLUMN parser_version VARCHAR(32) NOT NULL DEFAULT ''''', 'SELECT ''skip: parser_version exists''');
PREPARE s FROM @sql;
EXECUTE s;
DEALLOCATE PREPARE s;

SELECT COUNT(*) AS knowledge_document_rows FROM knowledge_document;
SELECT COUNT(*) AS knowledge_document_page_rows FROM knowledge_document_page;

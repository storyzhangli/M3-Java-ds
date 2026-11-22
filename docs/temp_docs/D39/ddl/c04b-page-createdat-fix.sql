ALTER TABLE knowledge_document_page MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
DELETE FROM knowledge_document_content WHERE document_id IN ('918da67867b248c98ac9587877db348a');
DELETE FROM knowledge_document WHERE document_id IN ('918da67867b248c98ac9587877db348a');
SELECT COUNT(*) AS doc_rows_total FROM knowledge_document;

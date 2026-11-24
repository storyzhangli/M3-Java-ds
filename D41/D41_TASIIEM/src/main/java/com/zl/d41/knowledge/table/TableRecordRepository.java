package com.zl.d41.knowledge.table;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 表格结构化记录持久层（JdbcTemplate 参数化 SQL，风格对齐 sync 包；
 * 物理表 d41_table_extract/d41_table_row 由 one 线在共享 ai_db 预建，two 线 IF NOT EXISTS 兼容适配，
 * version 物理 int 列——应用契约 String 数字串，KP2 裁决记录于 table-params.json）。
 */
@Repository
@ConditionalOnProperty(prefix = "app.knowledge.table", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class TableRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public TableRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 幂等 upsert（uk document_id+version+page_number+table_index），返回行 id。
     * 注意：预建表无 linked_chunk_ids_json 列（KP2 物理结构，禁 ALTER 他线资产），
     * 关联命中态持久化在 detect_flags（LINKAGE_MISSING），chunk 清单由读面实时计算。
     */
    Long upsertExtract(String documentId, int version, int pageNumber, int tableIndex,
                       String headerSnapshotJson, int rowCount, int colCount,
                       Integer sourceLineStart, Integer sourceLineEnd,
                       String detectFlags, String confidence,
                       Long continuationOfTableId, String status) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String sql = "INSERT INTO d41_table_extract"
                + " (document_id, version, page_number, table_index, header_snapshot_json,"
                + " row_count, col_count, source_line_start, source_line_end,"
                + " detect_flags, confidence,"
                + " continuation_of_table_id, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new"
                + " ON DUPLICATE KEY UPDATE"
                + " header_snapshot_json = new.header_snapshot_json,"
                + " row_count = new.row_count,"
                + " col_count = new.col_count,"
                + " source_line_start = new.source_line_start,"
                + " source_line_end = new.source_line_end,"
                + " detect_flags = new.detect_flags,"
                + " confidence = new.confidence,"
                + " continuation_of_table_id = new.continuation_of_table_id,"
                + " status = new.status,"
                + " updated_at = new.updated_at";
        jdbcTemplate.update(sql, documentId, version, pageNumber, tableIndex,
                headerSnapshotJson, rowCount, colCount, sourceLineStart, sourceLineEnd,
                detectFlags, confidence, continuationOfTableId, status, now, now);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM d41_table_extract"
                        + " WHERE document_id = ? AND version = ? AND page_number = ? AND table_index = ?",
                Long.class, documentId, version, pageNumber, tableIndex);
    }

    void upsertRow(long tableId, int rowIndex, String cellsJson, Integer sourceLineNumber, String recordHash) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String sql = "INSERT INTO d41_table_row"
                + " (table_id, row_index, cells_json, source_line_number, record_hash, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?) AS new"
                + " ON DUPLICATE KEY UPDATE cells_json = new.cells_json,"
                + " source_line_number = new.source_line_number,"
                + " record_hash = new.record_hash";
        jdbcTemplate.update(sql, tableId, rowIndex, cellsJson, sourceLineNumber, recordHash, now);
    }

    /** 关联态落旗标列（预建表无 linked_chunk_ids_json 列，chunk 清单读面实时算）。 */
    void updateLinkageFlags(long tableId, String detectFlags) {
        jdbcTemplate.update("UPDATE d41_table_extract SET detect_flags = ?, updated_at = ? WHERE id = ?",
                detectFlags, Timestamp.valueOf(LocalDateTime.now()), tableId);
    }

    /** 版本级清理（删除闭环/旧版清理同事务追加，D7）。 */
    int deleteByDocumentVersion(String documentId, int version) {
        int rows = jdbcTemplate.update(
                "DELETE tr FROM d41_table_row tr JOIN d41_table_extract te ON tr.table_id = te.id"
                        + " WHERE te.document_id = ? AND te.version = ?", documentId, version);
        int tables = jdbcTemplate.update(
                "DELETE FROM d41_table_extract WHERE document_id = ? AND version = ?",
                documentId, version);
        return rows + tables;
    }

    /** 文档级清理。 */
    int deleteByDocument(String documentId) {
        int rows = jdbcTemplate.update(
                "DELETE tr FROM d41_table_row tr JOIN d41_table_extract te ON tr.table_id = te.id"
                        + " WHERE te.document_id = ?", documentId);
        int tables = jdbcTemplate.update(
                "DELETE FROM d41_table_extract WHERE document_id = ?", documentId);
        return rows + tables;
    }

    List<Map<String, Object>> findTables(String documentId, String version, boolean activeOnly) {
        StringBuilder sql = new StringBuilder("SELECT id, document_id, version, page_number, table_index,"
                + " header_snapshot_json, row_count, col_count, source_line_start, source_line_end,"
                + " detect_flags, confidence, continuation_of_table_id, status"
                + " FROM d41_table_extract WHERE document_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(documentId);
        if (version != null && !version.isBlank()) {
            sql.append(" AND version = ?");
            args.add(Integer.parseInt(version.trim()));
        }
        if (activeOnly) {
            sql.append(" AND status = 'ACTIVE'");
        }
        sql.append(" ORDER BY page_number, table_index");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    Map<String, Object> findTableById(long tableId) {
        List<Map<String, Object>> found = jdbcTemplate.queryForList(
                "SELECT id, document_id, version, page_number, table_index, header_snapshot_json,"
                        + " row_count, col_count, source_line_start, source_line_end,"
                        + " detect_flags, confidence,"
                        + " continuation_of_table_id, status FROM d41_table_extract WHERE id = ?", tableId);
        return found.isEmpty() ? null : found.get(0);
    }

    List<Map<String, Object>> findRows(long tableId) {
        return jdbcTemplate.queryForList("SELECT row_index, cells_json, source_line_number, record_hash"
                + " FROM d41_table_row WHERE table_id = ? ORDER BY row_index", tableId);
    }

    /** 精确检索：JSON_TABLE 按 (列名,值) 严格配对（KP4 cellSchema 口径）。 */
    List<Map<String, Object>> queryByColumnValue(String documentId, String header, String value) {
        String base = "SELECT te.id AS table_id, te.page_number, te.table_index, te.version,"
                + " tr.row_index, tr.cells_json"
                + " FROM d41_table_extract te JOIN d41_table_row tr ON tr.table_id = te.id"
                + " WHERE te.document_id = ? AND te.status = 'ACTIVE'"
                + " AND EXISTS (SELECT 1 FROM JSON_TABLE(tr.cells_json,"
                + " '$[*]' COLUMNS (h VARCHAR(128) PATH '$.h', v VARCHAR(512) PATH '$.v')) jt"
                + " WHERE jt.v = ?";
        if (header == null || header.isBlank()) {
            return jdbcTemplate.queryForList(base + ")", documentId, value);
        }
        return jdbcTemplate.queryForList(base + " AND jt.h = ?)", documentId, value, header);
    }

    /** 双轨关联读取候选 Chunk（同文档同版本投影，MySQL 关联表快照）。 */
    List<Map<String, Object>> findChunksForLinkage(String documentId, String version) {
        return jdbcTemplate.queryForList(
                "SELECT chunk_id, page_numbers, content FROM vector_chunk_index"
                        + " WHERE document_id = ? AND version = ?",
                documentId, version);
    }

    /** 原始文件字节（knowledge_document_content.content 既有 blob 列，重抽取数据源）。 */
    byte[] findRawContent(String documentId) {
        List<byte[]> found = jdbcTemplate.query(
                "SELECT content FROM knowledge_document_content WHERE document_id = ?",
                (rs, rowNum) -> rs.getBytes(1), documentId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** TABLE_ORPHAN 检出：记录无活动文档（文档缺失或数值版本与登记版本不一致）。
     * 跨表 join 显式 COLLATE：d41_table_extract 为 one 线预建（utf8mb4_0900_ai_ci），
     * knowledge_document 为 general_ci，MySQL8 混合校对直接 1267，须两侧归一。 */
    List<Map<String, Object>> findOrphanRecords() {
        return jdbcTemplate.queryForList(
                "SELECT te.id, te.document_id, te.version, te.page_number, te.table_index"
                        + " FROM d41_table_extract te"
                        + " LEFT JOIN knowledge_document kd"
                        + " ON kd.document_id COLLATE utf8mb4_general_ci"
                        + " = te.document_id COLLATE utf8mb4_general_ci"
                        + " WHERE kd.document_id IS NULL"
                        + " OR (kd.version REGEXP '^[0-9]+$' AND te.version <> CAST(kd.version AS UNSIGNED))");
    }

    int countByDocumentPrefix(String prefix) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM d41_table_extract WHERE document_id LIKE ?", Integer.class, prefix + "%");
        return count == null ? 0 : count;
    }
}

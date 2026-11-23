package com.zl.d40.knowledge.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * d40_document_sync 持久层（MySQL，JdbcTemplate 参数化 SQL，风格沿用 VectorChunkRepository）。
 *
 * <p>幂等依赖数据库约束：唯一键 (document_id, operation, version) +
 * INSERT ... ON DUPLICATE KEY UPDATE，重复事件只覆盖状态字段不产生新行（E4 依据）。</p>
 */
@Slf4j
@Repository
public class DocumentSyncRepository {

    private static final String COLUMNS =
            "document_id, operation, version, sync_status, reason_code, attempt_count,"
                    + " next_retry_at, payload_hash, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public DocumentSyncRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按自然键插入/覆盖：冲突时更新状态字段，created_at 保持不变。
     */
    public void upsertOnNaturalKey(DocumentSyncRow row) {
        String sql = "INSERT INTO d40_document_sync (" + COLUMNS + ")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new"
                + " ON DUPLICATE KEY UPDATE"
                + " sync_status = new.sync_status,"
                + " reason_code = new.reason_code,"
                + " attempt_count = new.attempt_count,"
                + " next_retry_at = new.next_retry_at,"
                + " payload_hash = new.payload_hash,"
                + " updated_at = new.updated_at";
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(sql,
                row.getDocumentId(), row.getOperation(), blankToEmpty(row.getVersion()),
                row.getSyncStatus(), blankToEmpty(row.getReasonCode()), row.getAttemptCount(),
                toTimestamp(row.getNextRetryAt()), blankToEmpty(row.getPayloadHash()),
                toTimestamp(row.getCreatedAt() == null ? now : row.getCreatedAt()),
                toTimestamp(row.getUpdatedAt() == null ? now : row.getUpdatedAt()));
    }

    public Optional<DocumentSyncRow> find(String documentId, String operation, String version) {
        String sql = "SELECT * FROM d40_document_sync"
                + " WHERE document_id = ? AND operation = ? AND version = ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, documentId, operation,
                blankToEmpty(version)).stream().findFirst();
    }

    public List<DocumentSyncRow> findByDocument(String documentId) {
        String sql = "SELECT * FROM d40_document_sync WHERE document_id = ?"
                + " ORDER BY updated_at DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, documentId);
    }

    /**
     * 状态视图查询：documentId 可空（全表），limit 兜底防止无界返回。
     */
    public List<DocumentSyncRow> findRecent(String documentId, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM d40_document_sync");
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" WHERE document_id = ?");
            args.add(documentId);
        }
        sql.append(" ORDER BY updated_at DESC LIMIT ?");
        args.add(Math.max(1, limit));
        return jdbcTemplate.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    /**
     * 到期可自动重试：FAILED ∧ 非耗尽 ∧ attempt &lt; max ∧ next_retry_at 到期。
     */
    public List<DocumentSyncRow> findDueForRetry(int maxAttempts, LocalDateTime now) {
        String sql = "SELECT * FROM d40_document_sync WHERE sync_status = 'FAILED'"
                + " AND reason_code <> 'RETRY_EXHAUSTED' AND attempt_count < ?"
                + " AND (next_retry_at IS NULL OR next_retry_at <= ?)"
                + " ORDER BY next_retry_at";
        return jdbcTemplate.query(sql, ROW_MAPPER, maxAttempts, toTimestamp(now));
    }

    /**
     * 同步卡滞（DRIFT_SYNC_STUCK）：FAILED 超期未收敛或已耗尽待人工。
     */
    public List<DocumentSyncRow> findStuck(int stuckMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(stuckMinutes);
        String sql = "SELECT * FROM d40_document_sync WHERE sync_status = 'FAILED'"
                + " AND (reason_code = 'RETRY_EXHAUSTED' OR next_retry_at IS NULL"
                + " OR next_retry_at < ?) ORDER BY updated_at";
        return jdbcTemplate.query(sql, ROW_MAPPER, toTimestamp(cutoff));
    }

    /**
     * 状态迁移（只动状态字段，不新增行）；返回更新行数（0=自然键不存在）。
     */
    public int updateState(String documentId, String operation, String version,
                           String syncStatus, String reasonCode, int attemptCount,
                           LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        String sql = "UPDATE d40_document_sync SET sync_status = ?, reason_code = ?,"
                + " attempt_count = ?, next_retry_at = ?, updated_at = ?"
                + " WHERE document_id = ? AND operation = ? AND version = ?";
        return jdbcTemplate.update(sql, syncStatus, blankToEmpty(reasonCode), attemptCount,
                toTimestamp(nextRetryAt), toTimestamp(updatedAt),
                documentId, operation, blankToEmpty(version));
    }

    /**
     * 计数（夹具清理终验与 SYNCED 判定用）。
     */
    public long countByDocumentPrefix(String prefix) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM d40_document_sync WHERE document_id LIKE ?",
                Long.class, prefix + "%");
        return count == null ? 0L : count;
    }

    public int deleteByDocument(String documentId) {
        return jdbcTemplate.update("DELETE FROM d40_document_sync WHERE document_id = ?",
                documentId);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static final RowMapper<DocumentSyncRow> ROW_MAPPER = (rs, rowNum) -> {
        DocumentSyncRow row = new DocumentSyncRow();
        row.setId(rs.getLong("id"));
        row.setDocumentId(rs.getString("document_id"));
        row.setOperation(rs.getString("operation"));
        row.setVersion(rs.getString("version"));
        row.setSyncStatus(rs.getString("sync_status"));
        row.setReasonCode(rs.getString("reason_code"));
        row.setAttemptCount(rs.getInt("attempt_count"));
        row.setNextRetryAt(toLocalDateTime(rs, "next_retry_at"));
        row.setPayloadHash(rs.getString("payload_hash"));
        row.setCreatedAt(toLocalDateTime(rs, "created_at"));
        row.setUpdatedAt(toLocalDateTime(rs, "updated_at"));
        return row;
    };

    private static LocalDateTime toLocalDateTime(ResultSet rs, String column)
            throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

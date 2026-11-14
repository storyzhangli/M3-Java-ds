package com.zl.d31.knowledge.vectorindex.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 向量索引状态表与 Chunk 关联表的持久层（MySQL，JdbcTemplate 参数化 SQL）。
 *
 * <p>幂等依赖数据库约束而非 Java 内存判断：</p>
 * <ul>
 *   <li>{@code vector_index_task} 的唯一键 (document_id, version) 保证同文档版本只有一行任务；</li>
 *   <li>{@code vector_chunk_index} 的唯一键 (chunk_id) 与 (document_id, version, chunk_index)
 *       保证重复/并发写入最终只有一行；</li>
 *   <li>两个 Upsert 均为 INSERT ... ON DUPLICATE KEY UPDATE，重复执行等价于覆盖，不产生新行。</li>
 * </ul>
 */
@Slf4j
@Repository
public class VectorChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public VectorChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------
    // 任务行
    // ------------------------------------------------------------------

    public Optional<VectorIndexTaskRow> findTask(String documentId, String version) {
        String sql = "SELECT * FROM vector_index_task WHERE document_id = ? AND version = ?";
        return jdbcTemplate.query(sql, TASK_MAPPER, documentId, version).stream().findFirst();
    }

    public List<VectorIndexTaskRow> findTasksByDocument(String documentId) {
        String sql = "SELECT * FROM vector_index_task WHERE document_id = ? ORDER BY version";
        return jdbcTemplate.query(sql, TASK_MAPPER, documentId);
    }

    public List<VectorIndexTaskRow> findAllTasks() {
        return jdbcTemplate.query("SELECT * FROM vector_index_task", TASK_MAPPER);
    }

    public List<VectorIndexTaskRow> findTasksByStatus(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM vector_index_task WHERE status IN ("
                + placeholders(statuses.size()) + ")";
        return jdbcTemplate.query(sql, TASK_MAPPER, statuses.toArray());
    }

    public List<VectorIndexTaskRow> findStuckTasks(LocalDateTime cutoff) {
        String sql = "SELECT * FROM vector_index_task WHERE status IN ('PENDING', 'INDEXING')"
                + " AND updated_at < ? ORDER BY updated_at";
        return jdbcTemplate.query(sql, TASK_MAPPER, Timestamp.valueOf(cutoff));
    }

    public List<VectorIndexTaskRow> findRetryableTasks(int maxAttempts) {
        String sql = "SELECT * FROM vector_index_task"
                + " WHERE status IN ('PARTIAL', 'FAILED') AND attempt_count < ?"
                + " ORDER BY updated_at";
        return jdbcTemplate.query(sql, TASK_MAPPER, maxAttempts);
    }

    /**
     * 插入/覆盖任务行：唯一键 (document_id, version) 冲突时更新状态字段，created_at 保持不变。
     */
    public void upsertTask(VectorIndexTaskRow row) {
        String sql = "INSERT INTO vector_index_task"
                + " (document_id, version, strategy, content_hash, split_version, vector_space_id,"
                + " chunk_count, status, stage, error_code, error_message, attempt_count,"
                + " next_retry_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new"
                + " ON DUPLICATE KEY UPDATE"
                + " strategy = new.strategy,"
                + " content_hash = new.content_hash,"
                + " split_version = new.split_version,"
                + " vector_space_id = new.vector_space_id,"
                + " chunk_count = new.chunk_count,"
                + " status = new.status,"
                + " stage = new.stage,"
                + " error_code = new.error_code,"
                + " error_message = new.error_message,"
                + " attempt_count = new.attempt_count,"
                + " next_retry_at = new.next_retry_at,"
                + " updated_at = new.updated_at";
        jdbcTemplate.update(sql,
                row.getDocumentId(), row.getVersion(), row.getStrategy(), row.getContentHash(),
                row.getSplitVersion(), row.getVectorSpaceId(), row.getChunkCount(),
                notNull(row.getStatus()), notNull(row.getStage()), notNull(row.getErrorCode()),
                notNull(row.getErrorMessage()), row.getAttemptCount(),
                toTimestamp(row.getNextRetryAt()), toTimestamp(row.getCreatedAt()),
                toTimestamp(row.getUpdatedAt()));
    }

    /**
     * 按自然键更新任务状态字段（不新增行）。
     */
    public int updateTaskState(VectorIndexTaskRow row) {
        String sql = "UPDATE vector_index_task SET"
                + " strategy = ?, content_hash = ?, split_version = ?, vector_space_id = ?,"
                + " chunk_count = ?, status = ?, stage = ?, error_code = ?, error_message = ?,"
                + " attempt_count = ?, next_retry_at = ?, updated_at = ?"
                + " WHERE document_id = ? AND version = ?";
        return jdbcTemplate.update(sql,
                notNull(row.getStrategy()), notNull(row.getContentHash()),
                notNull(row.getSplitVersion()), notNull(row.getVectorSpaceId()),
                row.getChunkCount(), notNull(row.getStatus()), notNull(row.getStage()),
                notNull(row.getErrorCode()), notNull(row.getErrorMessage()),
                row.getAttemptCount(), toTimestamp(row.getNextRetryAt()),
                toTimestamp(row.getUpdatedAt()), row.getDocumentId(), row.getVersion());
    }

    /**
     * 只更新状态/阶段/错误/重试时间，不动指纹与载荷元数据（用于阶段推进与失败落账）。
     */
    public int updateTaskProgress(String documentId, String version, String status, String stage,
                                  String errorCode, String errorMessage, int attemptCount,
                                  LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        String sql = "UPDATE vector_index_task SET status = ?, stage = ?, error_code = ?,"
                + " error_message = ?, attempt_count = ?, next_retry_at = ?, updated_at = ?"
                + " WHERE document_id = ? AND version = ?";
        return jdbcTemplate.update(sql, notNull(status), notNull(stage), notNull(errorCode),
                notNull(errorMessage), attemptCount,
                toTimestamp(nextRetryAt), toTimestamp(updatedAt), documentId, version);
    }

    public int deleteTask(String documentId, String version) {
        return jdbcTemplate.update(
                "DELETE FROM vector_index_task WHERE document_id = ? AND version = ?",
                documentId, version);
    }

    public int deleteTasksByDocument(String documentId) {
        return jdbcTemplate.update(
                "DELETE FROM vector_index_task WHERE document_id = ?", documentId);
    }

    // ------------------------------------------------------------------
    // Chunk 关联行
    // ------------------------------------------------------------------

    public List<VectorChunkRow> findChunks(String documentId, String version) {
        String sql = "SELECT * FROM vector_chunk_index WHERE document_id = ? AND version = ?"
                + " ORDER BY chunk_index";
        return jdbcTemplate.query(sql, CHUNK_MAPPER, documentId, version);
    }

    public List<VectorChunkRow> findChunksByDocument(String documentId) {
        String sql = "SELECT * FROM vector_chunk_index WHERE document_id = ?"
                + " ORDER BY version, chunk_index";
        return jdbcTemplate.query(sql, CHUNK_MAPPER, documentId);
    }

    public List<VectorChunkRow> findAllChunks() {
        return jdbcTemplate.query("SELECT * FROM vector_chunk_index", CHUNK_MAPPER);
    }

    public int countChunks(String documentId, String version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_chunk_index WHERE document_id = ? AND version = ?",
                Integer.class, documentId, version);
        return count == null ? 0 : count;
    }

    /**
     * 插入/覆盖 Chunk 关联行；唯一键（chunk_id 或 document_id+version+chunk_index）冲突时更新。
     */
    public void upsertChunk(VectorChunkRow row) {
        String sql = "INSERT INTO vector_chunk_index"
                + " (document_id, version, strategy, chunk_id, chunk_index, content,"
                + " chunk_content_hash, character_count, page_numbers, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new"
                + " ON DUPLICATE KEY UPDATE"
                + " chunk_id = new.chunk_id,"
                + " strategy = new.strategy,"
                + " content = new.content,"
                + " chunk_content_hash = new.chunk_content_hash,"
                + " character_count = new.character_count,"
                + " page_numbers = new.page_numbers,"
                + " status = new.status,"
                + " updated_at = new.updated_at";
        jdbcTemplate.update(sql,
                row.getDocumentId(), row.getVersion(), notNull(row.getStrategy()),
                row.getChunkId(), row.getChunkIndex(), row.getContent(),
                notNull(row.getChunkContentHash()), row.getCharacterCount(),
                notNull(row.getPageNumbers()), notNull(row.getStatus()),
                toTimestamp(row.getCreatedAt()), toTimestamp(row.getUpdatedAt()));
    }

    public int deleteChunks(String documentId, String version) {
        return jdbcTemplate.update(
                "DELETE FROM vector_chunk_index WHERE document_id = ? AND version = ?",
                documentId, version);
    }

    public int deleteChunksByDocument(String documentId) {
        return jdbcTemplate.update(
                "DELETE FROM vector_chunk_index WHERE document_id = ?", documentId);
    }

    public int deleteChunkByChunkId(String chunkId) {
        return jdbcTemplate.update("DELETE FROM vector_chunk_index WHERE chunk_id = ?", chunkId);
    }

    /**
     * 批量写入/覆盖 Chunk 关联行（Outbox 快照阶段，状态通常为 PENDING）。
     *
     * <p>批量提交减少往返；唯一键冲突仍由 ON DUPLICATE KEY UPDATE 兜底，重复执行不产生新行。</p>
     */
    public void upsertChunks(List<VectorChunkRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO vector_chunk_index"
                + " (document_id, version, strategy, chunk_id, chunk_index, content,"
                + " chunk_content_hash, character_count, page_numbers, status, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS new"
                + " ON DUPLICATE KEY UPDATE"
                + " chunk_id = new.chunk_id,"
                + " strategy = new.strategy,"
                + " content = new.content,"
                + " chunk_content_hash = new.chunk_content_hash,"
                + " character_count = new.character_count,"
                + " page_numbers = new.page_numbers,"
                + " status = new.status,"
                + " updated_at = new.updated_at";
        jdbcTemplate.batchUpdate(sql, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.getDocumentId());
            ps.setString(2, row.getVersion());
            ps.setString(3, notNull(row.getStrategy()));
            ps.setString(4, row.getChunkId());
            ps.setInt(5, row.getChunkIndex());
            ps.setString(6, row.getContent());
            ps.setString(7, notNull(row.getChunkContentHash()));
            ps.setInt(8, row.getCharacterCount());
            ps.setString(9, notNull(row.getPageNumbers()));
            ps.setString(10, notNull(row.getStatus()));
            ps.setTimestamp(11, toTimestamp(row.getCreatedAt()));
            ps.setTimestamp(12, toTimestamp(row.getUpdatedAt()));
        });
    }

    /**
     * 按稳定 chunkId 批量推进 Chunk 行状态（PENDING → INDEXED）。
     */
    public int updateChunkStatuses(String documentId, String version,
                                   List<String> chunkIds, String status,
                                   LocalDateTime updatedAt) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE vector_chunk_index SET status = ?, updated_at = ?"
                + " WHERE document_id = ? AND version = ? AND chunk_id IN ("
                + placeholders(chunkIds.size()) + ")";
        Object[] args = new Object[4 + chunkIds.size()];
        args[0] = status;
        args[1] = Timestamp.valueOf(updatedAt);
        args[2] = documentId;
        args[3] = version;
        for (int i = 0; i < chunkIds.size(); i++) {
            args[4 + i] = chunkIds.get(i);
        }
        return jdbcTemplate.update(sql, args);
    }

    public int countChunksByStatus(String documentId, String version, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_chunk_index"
                        + " WHERE document_id = ? AND version = ? AND status = ?",
                Integer.class, documentId, version, status);
        return count == null ? 0 : count;
    }

    /**
     * 删除指定版本中 chunk_index >= fromIndex 的行（新版本 Chunk 数少于旧版本时清理尾部）。
     */
    public int deleteChunksFromIndex(String documentId, String version, int fromIndex) {
        return jdbcTemplate.update(
                "DELETE FROM vector_chunk_index WHERE document_id = ? AND version = ?"
                        + " AND chunk_index >= ?",
                documentId, version, fromIndex);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static String notNull(String value) {
        return value == null ? "" : value;
    }

    private String placeholders(int count) {
        List<String> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            parts.add("?");
        }
        return String.join(", ", parts);
    }

    private static Timestamp toTimestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime toLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static final RowMapper<VectorIndexTaskRow> TASK_MAPPER =
            (rs, rowNum) -> {
                VectorIndexTaskRow row = new VectorIndexTaskRow();
                row.setId(rs.getLong("id"));
                row.setDocumentId(rs.getString("document_id"));
                row.setVersion(rs.getString("version"));
                row.setStrategy(rs.getString("strategy"));
                row.setContentHash(rs.getString("content_hash"));
                row.setSplitVersion(rs.getString("split_version"));
                row.setVectorSpaceId(rs.getString("vector_space_id"));
                row.setChunkCount(rs.getInt("chunk_count"));
                row.setStatus(rs.getString("status"));
                row.setStage(rs.getString("stage"));
                row.setErrorCode(rs.getString("error_code"));
                row.setErrorMessage(rs.getString("error_message"));
                row.setAttemptCount(rs.getInt("attempt_count"));
                row.setNextRetryAt(toLocalDateTime(rs, "next_retry_at"));
                row.setCreatedAt(toLocalDateTime(rs, "created_at"));
                row.setUpdatedAt(toLocalDateTime(rs, "updated_at"));
                return row;
            };

    private static final RowMapper<VectorChunkRow> CHUNK_MAPPER =
            (rs, rowNum) -> {
                VectorChunkRow row = new VectorChunkRow();
                row.setId(rs.getLong("id"));
                row.setDocumentId(rs.getString("document_id"));
                row.setVersion(rs.getString("version"));
                row.setStrategy(rs.getString("strategy"));
                row.setChunkId(rs.getString("chunk_id"));
                row.setChunkIndex(rs.getInt("chunk_index"));
                row.setContent(rs.getString("content"));
                row.setChunkContentHash(rs.getString("chunk_content_hash"));
                row.setCharacterCount(rs.getInt("character_count"));
                row.setPageNumbers(rs.getString("page_numbers"));
                row.setStatus(rs.getString("status"));
                row.setCreatedAt(toLocalDateTime(rs, "created_at"));
                row.setUpdatedAt(toLocalDateTime(rs, "updated_at"));
                return row;
            };
}

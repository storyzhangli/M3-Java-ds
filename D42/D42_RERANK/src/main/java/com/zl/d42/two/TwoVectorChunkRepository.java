package com.zl.d42.two;

import com.zl.d42.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d42.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d42.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d42.two.TwoPgVectorConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * two_ 隔离链路专用持久层：{@code two_vector_index_task} / {@code two_vector_chunk_index}。
 *
 * <p>继承 {@link VectorChunkRepository} 仅为满足
 * {@code DocumentVectorIndexService} 的构造器类型契约；<b>覆写全部公开方法</b>，
 * SQL 全部指向 two_ 前缀新表，与本地链路旧表零共享。不注册为 Spring Bean
 * （由 {@link TwoPgVectorConfiguration} 手动构造），避免第二个
 * VectorChunkRepository Bean 引发按类型注入歧义。</p>
 *
 * <p>幂等约束与旧表同构：任务唯一键 (document_id, version)，Chunk 唯一键
 * (chunk_id) 与 (document_id, version, chunk_index)，Upsert 均为
 * INSERT ... ON DUPLICATE KEY UPDATE。稳定 chunkId 含 vectorSpaceId，
 * two_ 空间 ID 与 LOCAL 不同，即使写入旧表也不会撞键——但本类从表名层面彻底隔离。</p>
 */
@Slf4j
public class TwoVectorChunkRepository extends VectorChunkRepository {

    private static final String TASK_TABLE = "two_vector_index_task";
    private static final String CHUNK_TABLE = "two_vector_chunk_index";

    private final JdbcTemplate twoJdbcTemplate;

    public TwoVectorChunkRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
        this.twoJdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------
    // 任务行
    // ------------------------------------------------------------------

    @Override
    public Optional<VectorIndexTaskRow> findTask(String documentId, String version) {
        String sql = "SELECT * FROM " + TASK_TABLE + " WHERE document_id = ? AND version = ?";
        return twoJdbcTemplate.query(sql, TASK_MAPPER, documentId, version).stream().findFirst();
    }

    @Override
    public List<VectorIndexTaskRow> findTasksByDocument(String documentId) {
        String sql = "SELECT * FROM " + TASK_TABLE + " WHERE document_id = ? ORDER BY version";
        return twoJdbcTemplate.query(sql, TASK_MAPPER, documentId);
    }

    @Override
    public List<VectorIndexTaskRow> findAllTasks() {
        return twoJdbcTemplate.query("SELECT * FROM " + TASK_TABLE, TASK_MAPPER);
    }

    @Override
    public List<VectorIndexTaskRow> findTasksByStatus(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM " + TASK_TABLE + " WHERE status IN ("
                + placeholders(statuses.size()) + ")";
        return twoJdbcTemplate.query(sql, TASK_MAPPER, statuses.toArray());
    }

    @Override
    public List<VectorIndexTaskRow> findStuckTasks(LocalDateTime cutoff) {
        String sql = "SELECT * FROM " + TASK_TABLE + " WHERE status IN ('PENDING', 'INDEXING')"
                + " AND updated_at < ? ORDER BY updated_at";
        return twoJdbcTemplate.query(sql, TASK_MAPPER, Timestamp.valueOf(cutoff));
    }

    @Override
    public List<VectorIndexTaskRow> findRetryableTasks(int maxAttempts) {
        String sql = "SELECT * FROM " + TASK_TABLE
                + " WHERE status IN ('PARTIAL', 'FAILED') AND attempt_count < ?"
                + " ORDER BY updated_at";
        return twoJdbcTemplate.query(sql, TASK_MAPPER, maxAttempts);
    }

    @Override
    public void upsertTask(VectorIndexTaskRow row) {
        String sql = "INSERT INTO " + TASK_TABLE
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
        twoJdbcTemplate.update(sql,
                row.getDocumentId(), row.getVersion(), row.getStrategy(), row.getContentHash(),
                row.getSplitVersion(), row.getVectorSpaceId(), row.getChunkCount(),
                notNull(row.getStatus()), notNull(row.getStage()), notNull(row.getErrorCode()),
                notNull(row.getErrorMessage()), row.getAttemptCount(),
                toTimestamp(row.getNextRetryAt()), toTimestamp(row.getCreatedAt()),
                toTimestamp(row.getUpdatedAt()));
    }

    @Override
    public int updateTaskState(VectorIndexTaskRow row) {
        String sql = "UPDATE " + TASK_TABLE + " SET"
                + " strategy = ?, content_hash = ?, split_version = ?, vector_space_id = ?,"
                + " chunk_count = ?, status = ?, stage = ?, error_code = ?, error_message = ?,"
                + " attempt_count = ?, next_retry_at = ?, updated_at = ?"
                + " WHERE document_id = ? AND version = ?";
        return twoJdbcTemplate.update(sql,
                notNull(row.getStrategy()), notNull(row.getContentHash()),
                notNull(row.getSplitVersion()), notNull(row.getVectorSpaceId()),
                row.getChunkCount(), notNull(row.getStatus()), notNull(row.getStage()),
                notNull(row.getErrorCode()), notNull(row.getErrorMessage()),
                row.getAttemptCount(), toTimestamp(row.getNextRetryAt()),
                toTimestamp(row.getUpdatedAt()), row.getDocumentId(), row.getVersion());
    }

    @Override
    public int updateTaskProgress(String documentId, String version, String status, String stage,
                                  String errorCode, String errorMessage, int attemptCount,
                                  LocalDateTime nextRetryAt, LocalDateTime updatedAt) {
        String sql = "UPDATE " + TASK_TABLE + " SET status = ?, stage = ?, error_code = ?,"
                + " error_message = ?, attempt_count = ?, next_retry_at = ?, updated_at = ?"
                + " WHERE document_id = ? AND version = ?";
        return twoJdbcTemplate.update(sql, notNull(status), notNull(stage), notNull(errorCode),
                notNull(errorMessage), attemptCount,
                toTimestamp(nextRetryAt), toTimestamp(updatedAt), documentId, version);
    }

    @Override
    public int deleteTask(String documentId, String version) {
        return twoJdbcTemplate.update(
                "DELETE FROM " + TASK_TABLE + " WHERE document_id = ? AND version = ?",
                documentId, version);
    }

    @Override
    public int deleteTasksByDocument(String documentId) {
        return twoJdbcTemplate.update(
                "DELETE FROM " + TASK_TABLE + " WHERE document_id = ?", documentId);
    }

    // ------------------------------------------------------------------
    // Chunk 关联行
    // ------------------------------------------------------------------

    @Override
    public List<VectorChunkRow> findChunks(String documentId, String version) {
        String sql = "SELECT * FROM " + CHUNK_TABLE + " WHERE document_id = ? AND version = ?"
                + " ORDER BY chunk_index";
        return twoJdbcTemplate.query(sql, CHUNK_MAPPER, documentId, version);
    }

    @Override
    public List<VectorChunkRow> findChunksByDocument(String documentId) {
        String sql = "SELECT * FROM " + CHUNK_TABLE + " WHERE document_id = ?"
                + " ORDER BY version, chunk_index";
        return twoJdbcTemplate.query(sql, CHUNK_MAPPER, documentId);
    }

    @Override
    public List<VectorChunkRow> findAllChunks() {
        return twoJdbcTemplate.query("SELECT * FROM " + CHUNK_TABLE, CHUNK_MAPPER);
    }

    @Override
    public int countChunks(String documentId, String version) {
        Integer count = twoJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + CHUNK_TABLE + " WHERE document_id = ? AND version = ?",
                Integer.class, documentId, version);
        return count == null ? 0 : count;
    }

    @Override
    public void upsertChunk(VectorChunkRow row) {
        String sql = "INSERT INTO " + CHUNK_TABLE
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
        twoJdbcTemplate.update(sql,
                row.getDocumentId(), row.getVersion(), notNull(row.getStrategy()),
                row.getChunkId(), row.getChunkIndex(), row.getContent(),
                notNull(row.getChunkContentHash()), row.getCharacterCount(),
                notNull(row.getPageNumbers()), notNull(row.getStatus()),
                toTimestamp(row.getCreatedAt()), toTimestamp(row.getUpdatedAt()));
    }

    @Override
    public int deleteChunks(String documentId, String version) {
        return twoJdbcTemplate.update(
                "DELETE FROM " + CHUNK_TABLE + " WHERE document_id = ? AND version = ?",
                documentId, version);
    }

    @Override
    public int deleteChunksByDocument(String documentId) {
        return twoJdbcTemplate.update(
                "DELETE FROM " + CHUNK_TABLE + " WHERE document_id = ?", documentId);
    }

    @Override
    public int deleteChunkByChunkId(String chunkId) {
        return twoJdbcTemplate.update(
                "DELETE FROM " + CHUNK_TABLE + " WHERE chunk_id = ?", chunkId);
    }

    @Override
    public List<String> findExistingChunkIds(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT chunk_id FROM " + CHUNK_TABLE + " WHERE chunk_id IN ("
                + placeholders(chunkIds.size()) + ")";
        return twoJdbcTemplate.queryForList(sql, String.class, chunkIds.toArray());
    }

    @Override
    public void upsertChunks(List<VectorChunkRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + CHUNK_TABLE
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
        twoJdbcTemplate.batchUpdate(sql, rows, rows.size(), (ps, row) -> {
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

    @Override
    public int updateChunkStatuses(String documentId, String version,
                                   List<String> chunkIds, String status,
                                   LocalDateTime updatedAt) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE " + CHUNK_TABLE + " SET status = ?, updated_at = ?"
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
        return twoJdbcTemplate.update(sql, args);
    }

    @Override
    public int countChunksByStatus(String documentId, String version, String status) {
        Integer count = twoJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + CHUNK_TABLE
                        + " WHERE document_id = ? AND version = ? AND status = ?",
                Integer.class, documentId, version, status);
        return count == null ? 0 : count;
    }

    @Override
    public int deleteChunksFromIndex(String documentId, String version, int fromIndex) {
        return twoJdbcTemplate.update(
                "DELETE FROM " + CHUNK_TABLE + " WHERE document_id = ? AND version = ?"
                        + " AND chunk_index >= ?",
                documentId, version, fromIndex);
    }

    // ------------------------------------------------------------------
    // 辅助（与父类同构；父类映射器为 private，无法复用，按同构复制）
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
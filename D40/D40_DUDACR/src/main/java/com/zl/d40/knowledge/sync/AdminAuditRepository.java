package com.zl.d40.knowledge.sync;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * d40_admin_audit 写入层（参数化 INSERT；仅 enabled 装配链引用）。
 */
public class AdminAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdminAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AdminAuditRow row) {
        String sql = "INSERT INTO d40_admin_audit"
                + " (actor_user_id, tenant_id, role, action, target_document_id,"
                + " version, result, reason_code, request_id, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                row.getActorUserId(), blank(row.getTenantId()), blank(row.getRole()),
                row.getAction(), blank(row.getTargetDocumentId()), blank(row.getVersion()),
                row.getResult(), blank(row.getReasonCode()), row.getRequestId(),
                Timestamp.valueOf(row.getCreatedAt() == null
                        ? LocalDateTime.now() : row.getCreatedAt()));
    }

    /** 夹具清理辅助（前缀限定，H6）。 */
    public long countByTargetPrefix(String prefix) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM d40_admin_audit WHERE target_document_id LIKE ?",
                Long.class, prefix + "%");
        return count == null ? 0L : count;
    }

    public int deleteByTargetDocument(String targetDocumentId) {
        return jdbcTemplate.update(
                "DELETE FROM d40_admin_audit WHERE target_document_id = ?", targetDocumentId);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}

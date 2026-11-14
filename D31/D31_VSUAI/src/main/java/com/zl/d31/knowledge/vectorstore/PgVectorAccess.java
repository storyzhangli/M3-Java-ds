package com.zl.d31.knowledge.vectorstore;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 向量表只读访问（计数与对账用），与框架 PgVectorStore 共享同一连接池。
 *
 * <p>本类不作为通用 JdbcTemplate Bean 暴露：容器内若出现第二个 JdbcTemplate Bean，
 * Spring Boot 的 JdbcTemplateAutoConfiguration 会整体退让，导致业务侧注入歧义。
 * 由 {@link PgVectorStoreConfiguration} 显式创建并持有。</p>
 */
public class PgVectorAccess {

    private final JdbcTemplate jdbcTemplate;

    private final PgVectorStoreProperties properties;

    public PgVectorAccess(JdbcTemplate jdbcTemplate, PgVectorStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 指定文档版本的向量行数（按 metadata 中的 documentId + version 过滤）。
     */
    public long countByDocumentVersion(String documentId, String version) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable()
                + " WHERE (metadata->>'documentId') = ? AND (metadata->>'version') = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, documentId, version);
        return count == null ? 0L : count;
    }

    /**
     * 全表向量行（id + 归属元数据），用于孤儿/缺失核对。
     */
    public List<PgVectorRow> listAll() {
        String sql = "SELECT id::text AS id, metadata->>'documentId' AS document_id,"
                + " metadata->>'version' AS version FROM " + qualifiedTable();
        List<PgVectorRow> rows = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            rows.add(new PgVectorRow(rs.getString("id"), rs.getString("document_id"),
                    rs.getString("version")));
        });
        return rows;
    }

    public boolean tableExists() {
        String sql = "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = ? AND table_name = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                properties.getSchemaName(), properties.getTableName());
        return count != null && count > 0;
    }

    public String qualifiedTable() {
        return "\"" + properties.getSchemaName() + "\".\"" + properties.getTableName() + "\"";
    }

    /**
     * PG 向量行投影（只保留核对所需字段）。
     */
    public record PgVectorRow(String id, String documentId, String version) {
    }
}

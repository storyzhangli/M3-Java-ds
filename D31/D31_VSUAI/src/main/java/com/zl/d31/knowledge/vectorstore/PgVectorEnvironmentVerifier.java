package com.zl.d31.knowledge.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pgvector 环境只读检查器：验证连接、扩展、向量表、embedding 维度与相似度索引。
 *
 * <p>全部 SQL 均为只读查询，不执行任何 DDL/DML；embedding 列维度与配置维度不一致时
 * 记录到 problems 而不是静默截断或补零。</p>
 */
public class PgVectorEnvironmentVerifier {

    private static final Logger log = LoggerFactory.getLogger(PgVectorEnvironmentVerifier.class);

    /** 解析 format_type 输出的 vector(384) 形式。 */
    private static final Pattern VECTOR_TYPE = Pattern.compile("^vector\\((-?\\d+)\\)$");

    private final JdbcTemplate jdbcTemplate;
    private final PgVectorStoreProperties properties;

    public PgVectorEnvironmentVerifier(JdbcTemplate jdbcTemplate, PgVectorStoreProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public PgVectorEnvironmentReport verify() {
        List<String> problems = new ArrayList<>();
        LocalDateTime checkedAt = LocalDateTime.now();
        String qualified = qualifiedName();

        // 1. 连通性 + 版本（连接失败直接返回不可达报告，不做后续检查）
        String pgVersion;
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one == null || one != 1) {
                problems.add("SELECT 1 连通性检查未返回预期结果");
            }
            pgVersion = jdbcTemplate.queryForObject("SELECT version()", String.class);
        } catch (DataAccessException ex) {
            problems.add("PostgreSQL 连接失败: " + rootMessage(ex));
            log.warn("pgvector 环境检查：PostgreSQL 不可达，qualified={}", qualified);
            return new PgVectorEnvironmentReport(false, null, null, false, qualified,
                    null, null, List.of(), problems, checkedAt);
        }

        // 2. pgvector 扩展版本
        String extensionVersion = null;
        List<String> versions = jdbcTemplate.query(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'",
                (rs, rowNum) -> rs.getString(1));
        if (versions.isEmpty()) {
            problems.add("pgvector 扩展未安装");
        } else {
            extensionVersion = versions.get(0);
        }

        // 3. 向量表是否存在
        Boolean tableExists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)",
                Boolean.class, properties.getSchemaName(), properties.getTableName());
        boolean exists = Boolean.TRUE.equals(tableExists);
        if (!exists) {
            problems.add("向量表不存在: " + qualified);
        }

        // 4. embedding 列类型与维度
        String columnType = null;
        Integer columnDimensions = null;
        List<String> indexDefs = List.of();
        if (exists) {
            List<String> types = jdbcTemplate.query(
                    "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                            + "WHERE a.attrelid = ?::regclass AND a.attname = 'embedding' "
                            + "AND a.attnum > 0 AND NOT a.attisdropped",
                    (rs, rowNum) -> rs.getString(1), qualified);
            if (types.isEmpty()) {
                problems.add("向量表缺少 embedding 列: " + qualified);
            } else {
                columnType = types.get(0);
                columnDimensions = parseVectorDimensions(columnType);
                if (columnDimensions == null) {
                    problems.add("embedding 列类型不是 vector(n): " + columnType);
                } else if (columnDimensions != properties.getDimensions()) {
                    problems.add("embedding 列维度与配置不一致: 列=" + columnDimensions
                            + "，配置=" + properties.getDimensions());
                }
            }

            indexDefs = jdbcTemplate.query(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ? ORDER BY indexname",
                    (rs, rowNum) -> rs.getString(1),
                    properties.getSchemaName(), properties.getTableName());
            boolean hasCosineHnsw = indexDefs.stream()
                    .map(def -> def.toLowerCase(Locale.ROOT))
                    .anyMatch(def -> def.contains("using hnsw") && def.contains("vector_cosine_ops"));
            if (!hasCosineHnsw) {
                problems.add("未发现 HNSW + vector_cosine_ops 相似度索引");
            }
        }

        PgVectorEnvironmentReport report = new PgVectorEnvironmentReport(
                true, pgVersion, extensionVersion, exists, qualified,
                columnType, columnDimensions, indexDefs, problems, checkedAt);
        if (report.healthy()) {
            log.info("pgvector 环境检查通过: table={}, embedding={}, extension={}, pg={}",
                    qualified, columnType, extensionVersion, shortVersion(pgVersion));
        } else {
            log.warn("pgvector 环境检查发现问题: {}", problems);
        }
        return report;
    }

    private String qualifiedName() {
        return properties.getSchemaName() + "." + properties.getTableName();
    }

    private Integer parseVectorDimensions(String columnType) {
        if (columnType == null) {
            return null;
        }
        Matcher matcher = VECTOR_TYPE.matcher(columnType);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private String shortVersion(String pgVersion) {
        if (pgVersion == null) {
            return null;
        }
        int comma = pgVersion.indexOf(',');
        return (comma > 0 ? pgVersion.substring(0, comma) : pgVersion).strip();
    }
}

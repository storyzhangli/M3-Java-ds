package com.zl.d42.hybrid.keyword;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d42.common.ResultCode;
import com.zl.d42.hybrid.keyword.ChunkRow;
import com.zl.d42.knowledge.retrieval.RetrievalException;
import com.zl.d42.knowledge.vectorstore.PgVectorStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 倒排行加载器：对 PG {@code vector_store} 做一次性<b>只读</b>全表加载（P1.2 / D4）。
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>不新增 DataSource/JdbcTemplate Bean——主数据源是 MySQL，第二个 DataSource Bean
 *       会使 MyBatis/JdbcTemplate 自动配置整体退让（PgVectorStoreConfiguration 同款理由）。
 *       本类用短生命周期 {@code DriverManager} 连接，读完即关，30 行量级零压力；</li>
 *   <li>零写入、零 DDL、不建表；two_ 表零触碰；</li>
 *   <li>行集必须与向量路可见行集一致（同表同批行，含残留噪声行）；行数与 KP2 冻结值
 *       不一致时仅告警记录（如实反映现状），不清理、不干预（H8）。</li>
 * </ul>
 */
public final class HybridIndexLoader {

    private static final Logger log = LoggerFactory.getLogger(HybridIndexLoader.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HybridIndexLoader() {
    }

    /**
     * 全表加载并解析 metadata；任何数据库错误抛 {@code RetrievalException}
     * （INDEX_LOAD_FAILED，与"空结果"严格区分——EMPTY ≠ FAILED）。
     */
    public static List<ChunkRow> loadAll(PgVectorStoreProperties properties) {
        String table = "\"" + properties.getSchemaName() + "\".\"" + properties.getTableName() + "\"";
        String sql = "SELECT id::text AS id, content, metadata::text AS metadata FROM " + table;
        List<ChunkRow> rows = new ArrayList<>();
        long start = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(
                properties.getUrl(), properties.getUsername(), properties.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(toRow(rs.getString("id"), rs.getString("content"),
                        rs.getString("metadata")));
            }
        } catch (SQLException ex) {
            // 不回显 URL/账号等连接细节，仅记录 SQLState
            log.error("倒排加载失败: sqlState={}", ex.getSQLState());
            throw new RetrievalException(ResultCode.VECTOR_INDEX_FAILED,
                    "倒排索引加载失败（PG 只读连接或解析异常），混合检索不可用；无内存语料 fallback（§3.7）",
                    "INDEX_LOAD_FAILED", ex);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        log.info("倒排行加载完成: rows={}, elapsedMs={}（只读，零写入）", rows.size(), elapsedMillis);
        return List.copyOf(rows);
    }

    private static ChunkRow toRow(String id, String content, String metadataJson) {
        JsonNode metadata;
        try {
            metadata = (metadataJson == null || metadataJson.isBlank())
                    ? MAPPER.createObjectNode() : MAPPER.readTree(metadataJson);
        } catch (Exception ex) {
            throw new RetrievalException(ResultCode.VECTOR_INDEX_FAILED,
                    "倒排加载解析 metadata 失败，chunkId=" + id, "INDEX_METADATA_INVALID", ex);
        }
        return new ChunkRow(
                id,
                textOrNull(metadata, "documentId"),
                textOrNull(metadata, "version"),
                intOrNull(metadata, "chunkIndex"),
                textOrNull(metadata, "title"),
                pageNumbers(metadata.get("pageNumbers")),
                intOrNull(metadata, "paragraphIndex"),
                textOrNull(metadata, "strategy"),
                content,
                textOrNull(metadata, "lifecycleStatus"),
                dateTimeOrNull(metadata, "effectiveAt"),
                dateTimeOrNull(metadata, "expiresAt"),
                textOrNull(metadata, "tenantId"),
                textOrNull(metadata, "deptId"),
                textOrNull(metadata, "deviceId"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.intValue();
        }
        try {
            return Integer.valueOf(value.asText().strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 页码兼容数组/单值/缺失三形态（对齐 RetrievalHitMapper.asIntegerList 口径）。 */
    private static List<Integer> pageNumbers(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                Integer value = item.isNumber() ? item.intValue() : tryParse(item.asText());
                if (value != null) {
                    result.add(value);
                }
            }
        } else if (node.isNumber()) {
            result.add(node.intValue());
        } else {
            Integer single = tryParse(node.asText());
            if (single != null) {
                result.add(single);
            }
        }
        return result;
    }

    private static Integer tryParse(String value) {
        try {
            return Integer.valueOf(value.strip());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static LocalDateTime dateTimeOrNull(JsonNode node, String field) {
        String value = textOrNull(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}

package com.zl.d31.vectorstore;

import com.zl.d31.knowledge.vectorstore.PgVectorEnvironmentReport;
import com.zl.d31.knowledge.vectorstore.PgVectorEnvironmentVerifier;
import com.zl.d31.knowledge.vectorstore.PgVectorStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PgVectorEnvironmentVerifier 只读检查逻辑单元测试（Mock JdbcTemplate，无真实数据库）。
 */
class PgVectorEnvironmentVerifierTest {

    private PgVectorStoreProperties props() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setUrl("jdbc:postgresql://127.0.0.1:5432/spring_ai");
        properties.setUsername("postgres");
        return properties;
    }

    private JdbcTemplate jdbcTemplateWith(List<String> extVersions,
                                          List<String> columnTypes,
                                          List<String> indexDefs,
                                          Boolean tableExists) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT version()"), eq(String.class)))
                .thenReturn("PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2) on x86_64-pc-linux-gnu");
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any(), any()))
                .thenReturn(tableExists);
        // 2 参重载：扩展版本查询
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(extVersions);
        // 3 参（varargs）重载：列类型 / 索引定义
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("format_type")) {
                        return columnTypes;
                    }
                    if (sql.contains("pg_indexes")) {
                        return indexDefs;
                    }
                    return List.of();
                });
        return jdbcTemplate;
    }

    @Test
    @DisplayName("健康环境返回 healthy=true 且维度解析为 384")
    void healthyEnvironmentReport() {
        JdbcTemplate jdbcTemplate = jdbcTemplateWith(
                List.of("0.8.7"),
                List.of("vector(384)"),
                List.of("CREATE INDEX spring_ai_vector_index ON public.vector_store "
                        + "USING hnsw (embedding vector_cosine_ops)"),
                true);

        PgVectorEnvironmentReport report =
                new PgVectorEnvironmentVerifier(jdbcTemplate, props()).verify();

        assertThat(report.healthy()).isTrue();
        assertThat(report.reachable()).isTrue();
        assertThat(report.tableExists()).isTrue();
        assertThat(report.vectorExtensionVersion()).isEqualTo("0.8.7");
        assertThat(report.embeddingColumnType()).isEqualTo("vector(384)");
        assertThat(report.embeddingColumnDimensions()).isEqualTo(384);
        assertThat(report.problems()).isEmpty();
    }

    @Test
    @DisplayName("embedding 列维度 vector(128) 与配置 384 不一致时进入 problems")
    void detectsDimensionMismatch() {
        JdbcTemplate jdbcTemplate = jdbcTemplateWith(
                List.of("0.8.7"),
                List.of("vector(128)"),
                List.of("CREATE INDEX d31_idx ON public.vector_store "
                        + "USING hnsw (embedding vector_cosine_ops)"),
                true);

        PgVectorEnvironmentReport report =
                new PgVectorEnvironmentVerifier(jdbcTemplate, props()).verify();

        assertThat(report.healthy()).isFalse();
        assertThat(report.problems())
                .anyMatch(p -> p.contains("维度与配置不一致") && p.contains("128") && p.contains("384"));
    }

    @Test
    @DisplayName("pgvector 扩展缺失时进入 problems")
    void detectsMissingExtension() {
        JdbcTemplate jdbcTemplate = jdbcTemplateWith(
                List.of(),
                List.of("vector(384)"),
                List.of("CREATE INDEX d31_idx ON public.vector_store "
                        + "USING hnsw (embedding vector_cosine_ops)"),
                true);

        PgVectorEnvironmentReport report =
                new PgVectorEnvironmentVerifier(jdbcTemplate, props()).verify();

        assertThat(report.healthy()).isFalse();
        assertThat(report.vectorExtensionVersion()).isNull();
        assertThat(report.problems()).contains("pgvector 扩展未安装");
    }

    @Test
    @DisplayName("缺失 HNSW + vector_cosine_ops 索引时进入 problems")
    void detectsMissingCosineHnswIndex() {
        JdbcTemplate jdbcTemplate = jdbcTemplateWith(
                List.of("0.8.7"),
                List.of("vector(384)"),
                List.of("CREATE INDEX d31_idx ON public.vector_store USING ivfflat (embedding vector_l2_ops)"),
                true);

        PgVectorEnvironmentReport report =
                new PgVectorEnvironmentVerifier(jdbcTemplate, props()).verify();

        assertThat(report.healthy()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("HNSW"));
    }

    @Test
    @DisplayName("连接失败返回不可达报告，不抛异常")
    void reportsUnreachableInsteadOfThrowing() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenThrow(new DataAccessException("connection refused") {
                });

        PgVectorEnvironmentReport report =
                new PgVectorEnvironmentVerifier(jdbcTemplate, props()).verify();

        assertThat(report.reachable()).isFalse();
        assertThat(report.healthy()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("连接失败"));
    }
}

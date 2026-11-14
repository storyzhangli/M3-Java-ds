package com.zl.d31.vectorstore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d31.knowledge.embedding.EmbeddingProperties;
import com.zl.d31.knowledge.embedding.EmbeddingVectorValidator;
import com.zl.d31.knowledge.embedding.LocalHashNgramEmbeddingModel;
import com.zl.d31.knowledge.vectorstore.PgVectorEnvironmentReport;
import com.zl.d31.knowledge.vectorstore.PgVectorEnvironmentVerifier;
import com.zl.d31.knowledge.vectorstore.PgVectorStoreProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 pgvector 集成测试（L3-L5 验收）。
 *
 * <p>仅在显式设置环境变量 D31_PG_INTEGRATION=true 时执行，连接信息来自环境变量：
 * D31_PG_URL / D31_PG_USERNAME / D31_PG_PASSWORD（凭据绝不写入文件与 Git）。
 * 测试使用独立 smoke 表 d31_vector_smoke_test，只清理带 d31TestMarker 标记的
 * 测试数据，表与索引保留作为环境证据。</p>
 */
@EnabledIfEnvironmentVariable(named = "D31_PG_INTEGRATION", matches = "true")
class PgVectorStoreRealIntegrationTest {

    private static final String SMOKE_TABLE = "d31_vector_smoke_test";
    private static final String MARKER = "d31TestMarker";

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static PgVectorStore vectorStore;
    private static PgVectorEnvironmentVerifier verifier;

    @BeforeAll
    static void setUpDatabaseAndStore() {
        String url = System.getenv("D31_PG_URL");
        String username = System.getenv("D31_PG_USERNAME");
        String password = System.getenv("D31_PG_PASSWORD");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException(
                    "D31_PG_INTEGRATION=true 时还必须提供 D31_PG_URL / D31_PG_USERNAME / D31_PG_PASSWORD");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("d31-it-pool");
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 幂等 DDL：只创建，不删除已有数据
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS public." + SMOKE_TABLE + " ("
                + "id uuid PRIMARY KEY, content text, metadata json, embedding vector(384))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS d31_vector_smoke_hnsw_idx ON public."
                + SMOKE_TABLE + " USING hnsw (embedding vector_cosine_ops)");

        // 复用 D29 生产链：EmbeddingProperties -> EmbeddingModelDescriptor -> LocalHashNgramEmbeddingModel
        EmbeddingProperties properties = new EmbeddingProperties();
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(properties);
        LocalHashNgramEmbeddingModel embeddingModel =
                new LocalHashNgramEmbeddingModel(descriptor, new EmbeddingVectorValidator());

        vectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName("public")
                .vectorTableName(SMOKE_TABLE)
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(384)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .removeExistingVectorStoreTable(false)
                .initializeSchema(false)
                .build();

        PgVectorStoreProperties verifierProps = new PgVectorStoreProperties();
        verifierProps.setUrl(url);
        verifierProps.setUsername(username);
        verifierProps.setPassword(password);
        verifierProps.setTableName(SMOKE_TABLE);
        verifier = new PgVectorEnvironmentVerifier(jdbcTemplate, verifierProps);
    }

    @AfterEach
    void cleanOwnRows() {
        jdbcTemplate.update("DELETE FROM public." + SMOKE_TABLE
                + " WHERE metadata->>'" + MARKER + "' = 'true'");
    }

    @AfterAll
    static void tearDown() {
        try {
            jdbcTemplate.update("DELETE FROM public." + SMOKE_TABLE
                    + " WHERE metadata->>'" + MARKER + "' = 'true'");
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    private Document doc(String text, String documentId, int chunkIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MARKER, true);
        metadata.put("documentId", documentId);
        metadata.put("title", "D31 集成测试语料");
        metadata.put("version", "it-2026.11");
        metadata.put("chunkIndex", chunkIndex);
        return new Document(UUID.randomUUID().toString(), text, metadata);
    }

    @Test
    @DisplayName("L3: 环境只读检查通过（扩展 0.8.7 / 表 / vector(384) / HNSW cosine 索引）")
    void environmentIsHealthy() {
        PgVectorEnvironmentReport report = verifier.verify();

        assertThat(report.healthy())
                .withFailMessage("环境检查未通过: %s", report.problems())
                .isTrue();
        assertThat(report.vectorExtensionVersion()).isEqualTo("0.8.7");
        assertThat(report.embeddingColumnType()).isEqualTo("vector(384)");
        assertThat(report.embeddingColumnDimensions()).isEqualTo(384);
        assertThat(report.indexDefinitions().toString()).containsIgnoringCase("hnsw");
        assertThat(report.indexDefinitions().toString()).contains("vector_cosine_ops");
        System.out.println("[D31-IT] 环境报告: " + report);
    }

    @Test
    @DisplayName("L4+L5: 真实写入 5 条 384 维向量，COSINE 检索 top1 命中对应语料")
    void storeAndCosineSearchRoundTrip() {
        List<Document> corpus = List.of(
                doc("空气压缩机无法启动，按下启动按钮无响应，检查电源、接触器与压力开关。", "doc-001", 0),
                doc("电机运行温度过高，散热风扇转速下降，需清理风道并检查热继电器。", "doc-002", 0),
                doc("主轴轴承出现周期性异响，解体检查发现滚道剥落，建议更换轴承并补充润滑脂。", "doc-003", 0),
                doc("控制系统 PLC 通信中断，HMI 显示离线，检查网线与交换机端口。", "doc-004", 0),
                doc("液压系统压力不足，油泵噪声增大，检查油位与溢流阀。", "doc-005", 0));

        vectorStore.add(corpus);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM public." + SMOKE_TABLE + " WHERE metadata->>'"
                        + MARKER + "' = 'true'", Integer.class);
        assertThat(count).isEqualTo(5);
        System.out.println("[D31-IT] 写入后行数: " + count);

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("压缩机启动没有反应")
                .topK(2)
                .build());
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getMetadata().get("documentId")).isEqualTo("doc-001");
        assertThat(hits.get(0).getText()).contains("空气压缩机");
        System.out.println("[D31-IT] query=压缩机启动没有反应 -> top1 docId="
                + hits.get(0).getMetadata().get("documentId") + ", score=" + hits.get(0).getScore()
                + ", top2 score=" + hits.get(1).getScore());
    }

    @Test
    @DisplayName("L5: COSINE 语义验证——同文本自查询 score 接近 1，排序正确")
    void cosineSelfSimilarityAndOrdering() {
        String doc3Text = "主轴轴承出现周期性异响，解体检查发现滚道剥落，建议更换轴承并补充润滑脂。";
        vectorStore.add(List.of(
                doc(doc3Text, "doc-003", 0),
                doc("电机运行温度过高，散热风扇转速下降，需清理风道并检查热继电器。", "doc-002", 0)));

        // 1. 同文本自查询：cosine 相似度理论上等于 1（浮点误差内 > 0.99）
        List<Document> selfHits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(doc3Text)
                .topK(2)
                .build());
        assertThat(selfHits).hasSize(2);
        assertThat(selfHits.get(0).getMetadata().get("documentId")).isEqualTo("doc-003");
        assertThat(selfHits.get(0).getScore()).isNotNull();
        assertThat(selfHits.get(0).getScore()).isGreaterThan(0.99);
        System.out.println("[D31-IT] 同文本自查询 score=" + selfHits.get(0).getScore());

        // 2. 改写查询：top1 仍命中轴承文档，且 top1 score 不低于 top2
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("轴承异响需要润滑")
                .topK(2)
                .build());
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).getMetadata().get("documentId")).isEqualTo("doc-003");
        assertThat(hits.get(0).getScore()).isNotNull();
        assertThat(hits.get(1).getScore()).isNotNull();
        assertThat(hits.get(0).getScore()).isGreaterThanOrEqualTo(hits.get(1).getScore());
        assertThat(hits.get(0).getScore()).isPositive();
        System.out.println("[D31-IT] 轴承检索 top1 score=" + hits.get(0).getScore()
                + ", top2 score=" + hits.get(1).getScore());
    }

    @Test
    @DisplayName("L3: PostgreSQL 拒绝维度不匹配向量（3 维写入 vector(384) 列）")
    void postgresRejectsWrongDimensionVector() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO public." + SMOKE_TABLE + " (id, content, metadata, embedding) "
                        + "VALUES (?, '错误维度', '{}'::json, ?)",
                UUID.randomUUID().toString(), "[1,0,0]"))
                .isInstanceOf(DataAccessException.class);
        System.out.println("[D31-IT] 3 维向量写入 vector(384) 列已被 PostgreSQL 拒绝");
    }
}

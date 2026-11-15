package com.zl.d32.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zl.d32.knowledge.KnowledgeCorpus;
import com.zl.d32.knowledge.KnowledgeProperties;
import com.zl.d32.knowledge.chunking.DocumentChunk;
import com.zl.d32.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d32.knowledge.embedding.EmbeddingProperties;
import com.zl.d32.knowledge.embedding.EmbeddingVectorValidator;
import com.zl.d32.knowledge.embedding.LocalHashNgramEmbeddingModel;
import com.zl.d32.knowledge.retrieval.RetrievalException;
import com.zl.d32.knowledge.retrieval.config.RetrievalProperties;
import com.zl.d32.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d32.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d32.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d32.knowledge.retrieval.model.QueryVector;
import com.zl.d32.knowledge.retrieval.service.CorpusIndexService;
import com.zl.d32.knowledge.retrieval.service.QueryEmbeddingService;
import com.zl.d32.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d32.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d32.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d32.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d32.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d32.knowledge.vectorindex.support.ContentHasher;
import com.zl.d32.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d32.knowledge.vectorstore.PgVectorAccess;
import com.zl.d32.knowledge.vectorstore.PgVectorStoreProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D32 相似度检索与 Top-K：真实 MySQL + 真实 PostgreSQL/pgvector 集成测试（Level 1-5）。
 *
 * <p>仅当环境变量 {@code D32_RETRIEVAL_IT=true} 时执行；凭据全部来自环境变量，仓库与日志不保存口令：</p>
 * <ul>
 *   <li>D32_MYSQL_URL / D32_MYSQL_USERNAME / D32_MYSQL_PASSWORD</li>
 *   <li>D32_PG_URL / D32_PG_USERNAME / D32_PG_PASSWORD</li>
 * </ul>
 *
 * <p>隔离策略：PG 使用独立测试表 {@code d32_it_vector_store}，绝不写入主表 {@code vector_store}；
 * MySQL 复用 D31 的两张表，但所有 documentId 统一加 {@code d32-it-} 前缀，只清理自己的行。</p>
 */
@EnabledIfEnvironmentVariable(named = "D32_RETRIEVAL_IT", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimilarityRetrievalRealIntegrationTest {

    private static final String IT_TABLE = "d32_it_vector_store";

    private static final String PREFIX = "d32-it-";

    private static final String CORPUS_VERSION = "corpus-v1";

    private static final String COOLING_DOC = PREFIX + "DOC-CNC-COOLING-001";

    private static final String SERVO_DOC = PREFIX + "DOC-SERVO-TROUBLE-002";

    private static final String WORKORDER_DOC = PREFIX + "DOC-WORKORDER-SPEC-003";

    /** 10 个真实业务问题，全部基于 classpath 语料的真实内容编写。 */
    private static final List<String[]> BUSINESS_QUESTIONS = List.of(
            new String[]{"Q1", "数控机床主轴过热报警时应该先检查什么？"},
            new String[]{"Q2", "冷却液充足但反复出现过热报警，如何判断温度传感器故障？"},
            new String[]{"Q3", "冷却泵异响并且流量下降，应该怎么处理？"},
            new String[]{"Q4", "伺服驱动器反复报过载，排查顺序是什么？"},
            new String[]{"Q5", "位置偏差计数器溢出报警应该检查哪些部位？"},
            new String[]{"Q6", "导致整条产线停机的设备故障属于几级工单，响应时限是多少？"},
            new String[]{"Q7", "工单关闭时必须填写哪些内容？"},
            new String[]{"Q8", "设备故障工单如何分级，二级工单的响应时限是多久？"},
            new String[]{"Q9", "冷却系统日常巡检需要检查哪些项目？"},
            new String[]{"Q10", "伺服电机过载时可以直接提高电流限幅吗？"});

    private static HikariDataSource mysqlDataSource;

    private static HikariDataSource pgDataSource;

    private static JdbcTemplate mysqlJdbc;

    private static JdbcTemplate pgJdbc;

    private static EmbeddingModel embeddingModel;

    private static EmbeddingModelDescriptor descriptor;

    private static PgVectorStore vectorStore;

    private static VectorChunkRepository repository;

    private static DocumentVectorIndexService indexService;

    private static RetrievalProperties retrievalProperties;

    private static QueryEmbeddingService queryEmbeddingService;

    private static SimilarityRetrievalService retrievalService;

    private static CorpusIndexService corpusIndexService;

    private static KnowledgeCorpus knowledgeCorpus;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        String mysqlUrl = env("D32_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/ai_db?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String mysqlUser = env("D32_MYSQL_USERNAME", "root");
        String mysqlPassword = requireEnv("D32_MYSQL_PASSWORD");
        String pgUrl = env("D32_PG_URL",
                "jdbc:postgresql://192.168.200.153:5432/spring_ai");
        String pgUser = env("D32_PG_USERNAME", "postgres");
        String pgPassword = requireEnv("D32_PG_PASSWORD");

        HikariConfig mysqlConfig = new HikariConfig();
        mysqlConfig.setJdbcUrl(mysqlUrl);
        mysqlConfig.setUsername(mysqlUser);
        mysqlConfig.setPassword(mysqlPassword);
        mysqlConfig.setPoolName("d32-it-mysql");
        mysqlConfig.setMaximumPoolSize(8);
        mysqlDataSource = new HikariDataSource(mysqlConfig);
        mysqlJdbc = new JdbcTemplate(mysqlDataSource);
        executeSchema(mysqlJdbc, readClasspath("db/schema-vector-index.sql"));

        HikariConfig pgConfig = new HikariConfig();
        pgConfig.setJdbcUrl(pgUrl);
        pgConfig.setUsername(pgUser);
        pgConfig.setPassword(pgPassword);
        pgConfig.setPoolName("d32-it-pg");
        pgConfig.setMaximumPoolSize(8);
        pgDataSource = new HikariDataSource(pgConfig);
        pgJdbc = new JdbcTemplate(pgDataSource);
        pgJdbc.execute("CREATE TABLE IF NOT EXISTS public." + IT_TABLE + " ("
                + "id uuid PRIMARY KEY, content text, metadata json, embedding vector(384))");
        pgJdbc.execute("CREATE INDEX IF NOT EXISTS d32_it_vector_store_hnsw_idx ON public."
                + IT_TABLE + " USING hnsw (embedding vector_cosine_ops)");

        EmbeddingProperties embeddingProperties = new EmbeddingProperties();
        descriptor = EmbeddingModelDescriptor.from(embeddingProperties);
        embeddingModel = new LocalHashNgramEmbeddingModel(descriptor,
                new EmbeddingVectorValidator());

        PgVectorStoreProperties storeProperties = new PgVectorStoreProperties();
        storeProperties.setUrl(pgUrl);
        storeProperties.setUsername(pgUser);
        storeProperties.setPassword(pgPassword);
        storeProperties.setSchemaName("public");
        storeProperties.setTableName(IT_TABLE);

        vectorStore = PgVectorStore.builder(pgJdbc, embeddingModel)
                .schemaName("public")
                .vectorTableName(IT_TABLE)
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(384)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .removeExistingVectorStoreTable(false)
                .initializeSchema(false)
                .build();
        PgVectorAccess pgVectorAccess = new PgVectorAccess(pgJdbc, storeProperties);
        repository = new VectorChunkRepository(mysqlJdbc);

        VectorIndexProperties vectorIndexProperties = new VectorIndexProperties();
        vectorIndexProperties.setBatchSize(4);
        vectorIndexProperties.setStuckMinutes(1);
        vectorIndexProperties.validate();

        indexService = new DocumentVectorIndexService(vectorStore, pgVectorAccess, repository,
                new StableChunkIdGenerator(), new ContentHasher(), vectorIndexProperties,
                descriptor, null, new DataSourceTransactionManager(mysqlDataSource));

        retrievalProperties = new RetrievalProperties();
        retrievalProperties.validate();
        queryEmbeddingService = new QueryEmbeddingService(embeddingModel, descriptor,
                retrievalProperties);
        retrievalService = new SimilarityRetrievalService(vectorStore, queryEmbeddingService,
                retrievalProperties);

        KnowledgeProperties knowledgeProperties = new KnowledgeProperties();
        knowledgeProperties.setCorpusLocation("knowledge/equipment-fault-corpus.json");
        knowledgeCorpus = new KnowledgeCorpus(OBJECT_MAPPER, knowledgeProperties);
        knowledgeCorpus.load();
        corpusIndexService = new CorpusIndexService(knowledgeCorpus, indexService);

        // 真实语料入库（测试前缀隔离），为检索用例准备真实向量数据
        List<?> indexResults = corpusIndexService.indexCorpus(CORPUS_VERSION,
                "FIXED_LENGTH", PREFIX);
        if (indexResults.size() != 3) {
            throw new IllegalStateException("语料入库应覆盖 3 篇文档，实际 " + indexResults.size());
        }
        System.out.printf("[D32-IT] 语料入库完成: documents=%d, chunks=%d, version=%s%n",
                indexResults.size(), corpusChunkCount(), CORPUS_VERSION);
    }

    @AfterAll
    static void tearDown() {
        cleanOwnData();
        if (pgDataSource != null) {
            pgDataSource.close();
        }
        if (mysqlDataSource != null) {
            mysqlDataSource.close();
        }
    }

    // ------------------------------------------------------------------
    // Level 2：Query Embedding
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Level2 Query Embedding：真实生成 384 维、有限、非零向量，向量空间一致")
    void queryEmbeddingProduces384FiniteVector() {
        QueryVector queryVector = queryEmbeddingService.embed("数控机床主轴过热报警怎么处理");

        assertThat(queryVector.dimensions()).isEqualTo(384);
        float[] values = queryVector.values();
        assertThat(values).hasSize(384);
        double sumSquares = 0.0d;
        for (float value : values) {
            assertThat(Float.isFinite(value)).as("向量元素必须有限").isTrue();
            sumSquares += (double) value * value;
        }
        assertThat(sumSquares).isGreaterThan(0.0d);
        assertThat(queryVector.vectorSpaceId()).isEqualTo(descriptor.vectorSpaceId());
        assertThat(queryVector.modelName()).isEqualTo(descriptor.modelName());
        assertThat(queryVector.query()).isEqualTo("数控机床主轴过热报警怎么处理");

        System.out.printf("[D32-IT] QueryEmbedding dims=%d vectorSpace=%s norm=%.6f first5=%s elapsedMs=%d%n",
                queryVector.dimensions(), queryVector.vectorSpaceId(), Math.sqrt(sumSquares),
                Arrays.toString(Arrays.copyOf(values, 5)), queryVector.elapsedMillis());
    }

    @Test
    @Order(2)
    @DisplayName("空查询与纯空白查询被拒绝，errorCode=QUERY_BLANK")
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> queryEmbeddingService.embed("   "))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> assertThat(((RetrievalException) ex).getErrorCode())
                        .isEqualTo("QUERY_BLANK"));
        System.out.println("[D32-IT] 空查询拒绝通过: errorCode=QUERY_BLANK");
    }

    @Test
    @Order(3)
    @DisplayName("超长查询（2001 码点）被拒绝，errorCode=QUERY_TOO_LONG")
    void oversizedQueryIsRejected() {
        String oversized = "设".repeat(2001);
        assertThatThrownBy(() -> queryEmbeddingService.embed(oversized))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> assertThat(((RetrievalException) ex).getErrorCode())
                        .isEqualTo("QUERY_TOO_LONG"));
        System.out.println("[D32-IT] 超长查询拒绝通过: errorCode=QUERY_TOO_LONG");
    }

    // ------------------------------------------------------------------
    // 参数边界
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("Top-K 默认值：未传 topK 时实际生效 5")
    void defaultTopKIsFive() {
        RetrievalResponse response = retrievalService.search(
                new RetrievalQueryRequest("数控机床主轴过热报警", null, null, null, null));

        assertThat(response.effectiveTopK()).isEqualTo(5);
        assertThat(response.requestedTopK()).isNull();
        assertThat(response.hits()).hasSizeLessThanOrEqualTo(5);
        assertThat(response.queryEmbedding().dimensions()).isEqualTo(384);
        System.out.printf("[D32-IT] 默认 TopK 通过: effectiveTopK=%d hits=%d%n",
                response.effectiveTopK(), response.hitCount());
    }

    @Test
    @Order(5)
    @DisplayName("Top-K 边界：1 与 50 合法，0/-1/51 拒绝")
    void topKBoundaries() {
        RetrievalResponse one = retrievalService.search(
                new RetrievalQueryRequest("主轴过热", 1, null, null, null));
        assertThat(one.effectiveTopK()).isEqualTo(1);
        assertThat(one.hits()).hasSizeLessThanOrEqualTo(1);

        RetrievalResponse fifty = retrievalService.search(
                new RetrievalQueryRequest("故障", 50, null, null, null));
        assertThat(fifty.effectiveTopK()).isEqualTo(50);
        assertThat(fifty.hits().size()).isLessThanOrEqualTo(50);

        for (int illegal : new int[]{0, -1, 51}) {
            assertThatThrownBy(() -> retrievalService.search(
                    new RetrievalQueryRequest("主轴过热", illegal, null, null, null)))
                    .isInstanceOf(RetrievalException.class)
                    .satisfies(ex -> assertThat(((RetrievalException) ex).getErrorCode())
                            .isEqualTo("TOP_K_OUT_OF_RANGE"));
        }
        System.out.printf("[D32-IT] TopK 边界通过: top1=%d top50=%d%n",
                one.hitCount(), fifty.hitCount());
    }

    // ------------------------------------------------------------------
    // Level 3：真实 Top-5 检索
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("Level3 真实 Top-5：pgvector 返回结果，分数为余弦相似度且 0..1")
    void realTopFiveRetrieval() {
        RetrievalResponse response = retrievalService.search(
                new RetrievalQueryRequest("数控机床主轴过热报警先检查什么", 5, null, null, null));

        assertThat(response.hits()).isNotEmpty();
        assertThat(response.hits().size()).isLessThanOrEqualTo(5);
        assertThat(response.hitCount()).isEqualTo(response.hits().size());
        for (RetrievalHitView hit : response.hits()) {
            assertThat(hit.score()).isNotNull();
            assertThat(hit.score()).isBetween(0.0d, 1.0d);
            assertThat(hit.content()).isNotBlank();
        }
        RetrievalHitView top1 = response.hits().get(0);
        assertThat(top1.documentId()).isEqualTo(COOLING_DOC);
        // score 与 distance 互补：score = 1 - cosine distance
        assertThat(top1.score() + top1.distance()).isCloseTo(1.0d,
                org.assertj.core.data.Offset.offset(1e-4));
        System.out.printf("[D32-IT] 真实 Top5 通过: hits=%d top1=%s score=%.6f distance=%.6f elapsedMs=%d%n",
                response.hitCount(), top1.chunkId(), top1.score(), top1.distance(),
                response.elapsedMillis());
    }

    @Test
    @Order(7)
    @DisplayName("分数语义：用 Chunk 原文自查询时 top1 命中自身且 score≈1.0、distance≈0")
    void selfQueryScoresOne() {
        String coolingExcerpt = knowledgeCorpus.allChunks().stream()
                .filter(chunk -> "DOC-CNC-COOLING-001".equals(chunk.documentId())
                        && "DOC-CNC-COOLING-001-C01".equals(chunk.chunkId()))
                .map(chunk -> chunk.excerpt())
                .findFirst()
                .orElseThrow();
        String expectedChunkId = repository.findChunks(COOLING_DOC, CORPUS_VERSION).stream()
                .filter(row -> row.getChunkIndex() == 0)
                .map(VectorChunkRow::getChunkId)
                .findFirst()
                .orElseThrow();

        RetrievalResponse response = retrievalService.search(
                new RetrievalQueryRequest(coolingExcerpt, 3, null, null, null));

        assertThat(response.hits()).isNotEmpty();
        RetrievalHitView top1 = response.hits().get(0);
        assertThat(top1.chunkId()).isEqualTo(expectedChunkId);
        assertThat(top1.score()).isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(top1.distance()).isCloseTo(0.0d, org.assertj.core.data.Offset.offset(1e-5));
        System.out.printf("[D32-IT] 自查询语义通过: chunkId=%s score=%.6f distance=%.6f%n",
                top1.chunkId(), top1.score(), top1.distance());
    }

    // ------------------------------------------------------------------
    // Level 4：来源映射与元数据过滤
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("Level4 来源映射：documentId/version/chunkIndex/title/pageNumbers/strategy 全部真实")
    void sourceMappingIsComplete() {
        RetrievalResponse response = retrievalService.search(
                new RetrievalQueryRequest("冷却泵异响 流量下降", 3, null, null, null));

        assertThat(response.hits()).isNotEmpty();
        RetrievalHitView top1 = response.hits().get(0);
        assertThat(top1.rank()).isEqualTo(1);
        assertThat(top1.chunkId()).hasSize(36);
        assertThat(top1.documentId()).isEqualTo(COOLING_DOC);
        assertThat(top1.version()).isEqualTo(CORPUS_VERSION);
        assertThat(top1.chunkIndex()).isNotNull();
        assertThat(top1.title()).contains("数控机床冷却系统维护手册");
        assertThat(top1.pageNumbers()).isNotEmpty();
        assertThat(top1.strategy()).isEqualTo("FIXED_LENGTH");
        assertThat(top1.scoreSemantic()).isEqualTo("COSINE_SIMILARITY");
        assertThat(top1.distanceSemantic()).isEqualTo("COSINE_DISTANCE");
        System.out.printf("[D32-IT] 来源映射通过: documentId=%s version=%s chunkIndex=%d pages=%s title=%s%n",
                top1.documentId(), top1.version(), top1.chunkIndex(), top1.pageNumbers(),
                top1.title());
    }

    @Test
    @Order(9)
    @DisplayName("Level4 元数据过滤：documentId 过滤后只返回目标文档，结果集收缩")
    void documentIdFilterReturnsOnlyTargetDocument() {
        RetrievalQueryRequest unfilteredRequest = new RetrievalQueryRequest(
                "设备 故障 检查 处理 工单", 10, null, null, null);
        RetrievalResponse unfiltered = retrievalService.search(unfilteredRequest);
        RetrievalResponse filtered = retrievalService.search(new RetrievalQueryRequest(
                "设备 故障 检查 处理 工单", 10, COOLING_DOC, CORPUS_VERSION, null));

        assertThat(filtered.hits()).allMatch(hit -> COOLING_DOC.equals(hit.documentId()));
        assertThat(filtered.hits().size()).isLessThanOrEqualTo(unfiltered.hits().size());
        assertThat(filtered.filterDescription()).contains("documentId=" + COOLING_DOC);

        long unfilteredDocuments = unfiltered.hits().stream()
                .map(RetrievalHitView::documentId).distinct().count();
        System.out.printf("[D32-IT] 过滤通过: 无过滤 hits=%d 文档数=%d -> 过滤后 hits=%d%n",
                unfiltered.hitCount(), unfilteredDocuments, filtered.hitCount());
    }

    @Test
    @Order(10)
    @DisplayName("不同查询返回不同结果：冷却泵问题与工单关单问题 top1 不同文档")
    void differentQueriesReturnDifferentTopHits() {
        RetrievalResponse cooling = retrievalService.search(
                new RetrievalQueryRequest("冷却泵异响，流量下降怎么处理", 5, null, null, null));
        RetrievalResponse workorder = retrievalService.search(
                new RetrievalQueryRequest("工单关闭必须填写哪些内容", 5, null, null, null));

        assertThat(cooling.hits()).isNotEmpty();
        assertThat(workorder.hits()).isNotEmpty();
        assertThat(cooling.hits().get(0).chunkId())
                .isNotEqualTo(workorder.hits().get(0).chunkId());
        assertThat(cooling.hits().get(0).documentId()).isEqualTo(COOLING_DOC);
        assertThat(workorder.hits().get(0).documentId()).isEqualTo(WORKORDER_DOC);
        System.out.printf("[D32-IT] 查询差异通过: coolingTop1=%s workorderTop1=%s%n",
                cooling.hits().get(0).chunkId(), workorder.hits().get(0).chunkId());
    }

    @Test
    @Order(11)
    @DisplayName("过滤值注入被拒绝：含单引号的 documentId 抛 FILTER_VALUE_INVALID")
    void invalidFilterValueIsRejected() {
        assertThatThrownBy(() -> retrievalService.search(new RetrievalQueryRequest(
                "主轴过热", 5, COOLING_DOC + "' OR 1=1", null, null)))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> assertThat(((RetrievalException) ex).getErrorCode())
                        .isEqualTo("FILTER_VALUE_INVALID"));
        System.out.println("[D32-IT] 过滤值注入拒绝通过: errorCode=FILTER_VALUE_INVALID");
    }

    // ------------------------------------------------------------------
    // Level 5：10 个真实业务问题 Top-5 记录（人工标注待确认）
    // ------------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("10 个真实业务问题 Top-5 检索并导出原始结果（标注状态=PENDING_HUMAN_CONFIRMATION）")
    void exportTenQuestionTopFiveEvaluation() throws IOException {
        List<Map<String, Object>> questions = new ArrayList<>(BUSINESS_QUESTIONS.size());
        for (String[] question : BUSINESS_QUESTIONS) {
            RetrievalResponse response = retrievalService.search(
                    new RetrievalQueryRequest(question[1], 5, null, null, null));
            assertThat(response.effectiveTopK()).isEqualTo(5);
            assertThat(response.hits().size()).isLessThanOrEqualTo(5);

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", question[0]);
            record.put("query", question[1]);
            record.put("hitCount", response.hitCount());
            record.put("elapsedMillis", response.elapsedMillis());
            record.put("filter", response.filterDescription());
            record.put("hits", response.hits());
            questions.add(record);

            System.out.printf("[D32-IT] %s hits=%d top1=%s score=%.6f%n",
                    question[0], response.hitCount(),
                    response.hits().isEmpty() ? "-" : response.hits().get(0).chunkId(),
                    response.hits().isEmpty() ? 0.0d : response.hits().get(0).score());
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("generatedAt", LocalDateTime.now().toString());
        snapshot.put("corpusVersion", CORPUS_VERSION);
        snapshot.put("topK", 5);
        snapshot.put("scoreSemantic", "COSINE_SIMILARITY");
        snapshot.put("distanceSemantic", "COSINE_DISTANCE");
        snapshot.put("annotationStatus", "PENDING_HUMAN_CONFIRMATION");
        snapshot.put("note", "相关性标注必须由用户或明确参与评估的人完成；本文件不含任何人工标注结论。");
        snapshot.put("questions", questions);

        Path evidenceDir = Paths.get(System.getProperty("user.dir"))
                .resolve("../../docs/evidence/D32").normalize();
        Files.createDirectories(evidenceDir);
        Path output = evidenceDir.resolve("retrieval-raw-top5.json");
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), snapshot);

        assertThat(Files.exists(output)).isTrue();
        System.out.printf("[D32-IT] 评估快照已导出: %s (%d bytes)%n",
                output.toAbsolutePath(), Files.size(output));
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static int corpusChunkCount() {
        return knowledgeCorpus.allChunks().size();
    }

    private static void cleanOwnData() {
        for (String documentId : List.of(COOLING_DOC, SERVO_DOC, WORKORDER_DOC)) {
            try {
                indexService.deleteByDocument(documentId);
            } catch (RuntimeException ex) {
                System.out.println("[D32-IT] 清理失败(忽略): " + documentId
                        + " -> " + ex.getClass().getSimpleName());
            }
        }
    }

    private static void executeSchema(JdbcTemplate jdbc, String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        for (String statement : cleaned.toString().split(";")) {
            String sql = statement.strip();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
    }

    private static String readClasspath(String path) {
        try (InputStream input = SimilarityRetrievalRealIntegrationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("classpath 资源不存在: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 classpath 资源失败: " + path, ex);
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name + "（凭据不得写入仓库）");
        }
        return value;
    }
}

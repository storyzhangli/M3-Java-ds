package com.zl.d31.vectorindex;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zl.d31.knowledge.chunking.ChunkBoundaryType;
import com.zl.d31.knowledge.chunking.ChunkSource;
import com.zl.d31.knowledge.chunking.ChunkStrategy;
import com.zl.d31.knowledge.chunking.DocumentChunk;
import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d31.knowledge.embedding.EmbeddingProperties;
import com.zl.d31.knowledge.embedding.EmbeddingVectorValidator;
import com.zl.d31.knowledge.embedding.LocalHashNgramEmbeddingModel;
import com.zl.d31.knowledge.vectorindex.VectorIndexException;
import com.zl.d31.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d31.knowledge.vectorindex.dto.ReconciliationReportView;
import com.zl.d31.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.knowledge.vectorindex.model.ChunkRowStatus;
import com.zl.d31.knowledge.vectorindex.model.VectorIndexStatus;
import com.zl.d31.knowledge.vectorindex.repository.VectorChunkRepository;
import com.zl.d31.knowledge.vectorindex.repository.VectorChunkRow;
import com.zl.d31.knowledge.vectorindex.repository.VectorIndexTaskRow;
import com.zl.d31.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d31.knowledge.vectorindex.service.VectorReconciliationService;
import com.zl.d31.knowledge.vectorindex.support.ContentHasher;
import com.zl.d31.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d31.knowledge.vectorstore.PgVectorAccess;
import com.zl.d31.knowledge.vectorstore.PgVectorStoreProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D31 向量入库、更新与幂等：真实 MySQL + 真实 PostgreSQL/pgvector 集成测试（Level 1-5）。
 *
 * <p>仅在显式设置环境变量 D31_VECTOR_INDEX_IT=true 时执行；连接信息全部来自环境变量，
 * 仓库与日志中不保存任何口令：</p>
 * <ul>
 *   <li>D31_MYSQL_URL / D31_MYSQL_USERNAME / D31_MYSQL_PASSWORD</li>
 *   <li>D31_PG_URL / D31_PG_USERNAME / D31_PG_PASSWORD</li>
 * </ul>
 *
 * <p>隔离策略：MySQL 使用真实 ai_db 的两张 D31 表（幂等 DDL，自动建表），
 * PostgreSQL 使用独立表 d31_it_vector_store，绝不触碰主表 vector_store；
 * 所有测试 documentId 以 d31-it- 开头，只清理自己的行，不删除表与扩展。</p>
 */
@EnabledIfEnvironmentVariable(named = "D31_VECTOR_INDEX_IT", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VectorIndexRealIntegrationTest {

    private static final String IT_TABLE = "d31_it_vector_store";

    private static final String PREFIX = "d31-it-";

    private static HikariDataSource mysqlDataSource;

    private static HikariDataSource pgDataSource;

    private static JdbcTemplate mysqlJdbc;

    private static JdbcTemplate pgJdbc;

    private static VectorChunkRepository repository;

    private static PgVectorAccess pgVectorAccess;

    private static PgVectorStore vectorStore;

    private static EmbeddingModel embeddingModel;

    private static EmbeddingModelDescriptor descriptor;

    private static VectorIndexProperties properties;

    private static DocumentVectorIndexService indexService;

    private static VectorReconciliationService reconciliationService;

    @BeforeAll
    static void setUp() {
        String mysqlUrl = env("D31_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/ai_db?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String mysqlUser = env("D31_MYSQL_USERNAME", "root");
        String mysqlPassword = requireEnv("D31_MYSQL_PASSWORD");
        String pgUrl = env("D31_PG_URL", "jdbc:postgresql://192.168.200.153:5432/spring_ai");
        String pgUser = env("D31_PG_USERNAME", "postgres");
        String pgPassword = requireEnv("D31_PG_PASSWORD");

        HikariConfig mysqlConfig = new HikariConfig();
        mysqlConfig.setJdbcUrl(mysqlUrl);
        mysqlConfig.setUsername(mysqlUser);
        mysqlConfig.setPassword(mysqlPassword);
        mysqlConfig.setPoolName("d31-it-mysql");
        mysqlConfig.setMaximumPoolSize(8);
        mysqlDataSource = new HikariDataSource(mysqlConfig);
        mysqlJdbc = new JdbcTemplate(mysqlDataSource);
        executeSchema(mysqlJdbc, readClasspath("db/schema-vector-index.sql"));

        HikariConfig pgConfig = new HikariConfig();
        pgConfig.setJdbcUrl(pgUrl);
        pgConfig.setUsername(pgUser);
        pgConfig.setPassword(pgPassword);
        pgConfig.setPoolName("d31-it-pg");
        pgConfig.setMaximumPoolSize(8);
        pgDataSource = new HikariDataSource(pgConfig);
        pgJdbc = new JdbcTemplate(pgDataSource);
        pgJdbc.execute("CREATE TABLE IF NOT EXISTS public." + IT_TABLE + " ("
                + "id uuid PRIMARY KEY, content text, metadata json, embedding vector(384))");
        pgJdbc.execute("CREATE INDEX IF NOT EXISTS d31_it_vector_store_hnsw_idx ON public."
                + IT_TABLE + " USING hnsw (embedding vector_cosine_ops)");

        EmbeddingProperties embeddingProperties = new EmbeddingProperties();
        descriptor = EmbeddingModelDescriptor.from(embeddingProperties);
        embeddingModel = new LocalHashNgramEmbeddingModel(
                descriptor, new EmbeddingVectorValidator());

        PgVectorStoreProperties storeProperties = new PgVectorStoreProperties();
        storeProperties.setUrl(pgUrl);
        storeProperties.setUsername(pgUser);
        storeProperties.setPassword(pgPassword);
        storeProperties.setSchemaName("public");
        storeProperties.setTableName(IT_TABLE);

        vectorStore = buildStore(pgJdbc, embeddingModel);
        pgVectorAccess = new PgVectorAccess(pgJdbc, storeProperties);
        repository = new VectorChunkRepository(mysqlJdbc);

        properties = new VectorIndexProperties();
        properties.setBatchSize(2);
        properties.setStuckMinutes(1);
        properties.setMaxAttempts(5);
        properties.setRetryBackoffMs(200);
        properties.validate();

        indexService = newService(vectorStore, pgVectorAccess);
        reconciliationService = new VectorReconciliationService(
                vectorStore, pgVectorAccess, repository, indexService, properties);
    }

    @AfterAll
    static void tearDown() {
        cleanOwnRows();
        if (pgDataSource != null) {
            pgDataSource.close();
        }
        if (mysqlDataSource != null) {
            mysqlDataSource.close();
        }
    }

    @BeforeEach
    void cleanBeforeEach() {
        cleanOwnRows();
    }

    // ------------------------------------------------------------------
    // 场景 1：新增导入
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("场景1 新增：3 个 Chunk 真实写入 PG，MySQL 关联 3 行，向量可检索")
    void firstImportWritesAllChunks() {
        String documentId = PREFIX + "a";
        VectorIndexResultView result = indexService.indexChunks(documentId, "v1",
                "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v1", 3, "初始"));

        assertThat(result.getStatus()).isEqualTo(VectorIndexStatus.COMPLETED.name());
        assertThat(result.getChunkCount()).isEqualTo(3);
        assertThat(result.getProcessed()).isEqualTo(3);
        assertThat(result.isIdempotent()).isFalse();
        assertThat(pgCount(documentId, "v1")).isEqualTo(3);
        assertThat(repository.countChunks(documentId, "v1")).isEqualTo(3);
        assertThat(repository.countChunksByStatus(documentId, "v1",
                ChunkRowStatus.INDEXED.name())).isEqualTo(3);

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query("集成测试 第 1 块 编号 1")
                .topK(3)
                .build());
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getMetadata().get("documentId")).isEqualTo(documentId);
        System.out.printf("[D31-IT] 场景1 新增通过：pg=%d relation=%d top1=%s%n",
                pgCount(documentId, "v1"), repository.countChunks(documentId, "v1"),
                hits.get(0).getMetadata().get("chunkIndex"));
    }

    // ------------------------------------------------------------------
    // 场景 2：重复导入幂等
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("场景2 重复导入：第二次命中幂等短路，PG 与 MySQL 行数不变")
    void repeatedImportIsIdempotent() {
        String documentId = PREFIX + "a";
        List<DocumentChunk> payload = chunks(documentId, "v1", 3, "初始");
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试", payload);

        VectorIndexResultView second = indexService.indexChunks(documentId, "v1",
                "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v1", 3, "初始"));

        assertThat(second.isIdempotent()).isTrue();
        assertThat(second.getProcessed()).isZero();
        assertThat(second.getStatus()).isEqualTo(VectorIndexStatus.COMPLETED.name());
        assertThat(pgCount(documentId, "v1")).isEqualTo(3);
        assertThat(repository.countChunks(documentId, "v1")).isEqualTo(3);
        assertThat(repository.findTasksByDocument(documentId)).hasSize(1);
        System.out.printf("[D31-IT] 场景2 幂等通过：processed=%d idempotent=%s%n",
                second.getProcessed(), second.isIdempotent());
    }

    // ------------------------------------------------------------------
    // 场景：同版本内容变化（稳定 ID 覆盖 + 旧行清理）
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("同版本内容变化：稳定 ID 覆盖为 2 行，旧的第 3 块被清理，无重复")
    void sameVersionContentChangeReplacesRows() {
        String documentId = PREFIX + "a";
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v1", 3, "初始"));

        VectorIndexResultView updated = indexService.indexChunks(documentId, "v1",
                "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v1", 2, "改版"));

        assertThat(updated.getStatus()).isEqualTo(VectorIndexStatus.COMPLETED.name());
        assertThat(updated.getChunkCount()).isEqualTo(2);
        assertThat(pgCount(documentId, "v1")).isEqualTo(2);
        assertThat(repository.countChunks(documentId, "v1")).isEqualTo(2);
        assertThat(pgDistinctCount(documentId, "v1")).isEqualTo(2);
        System.out.printf("[D31-IT] 同版本覆盖通过：pg=%d relation=%d%n",
                pgCount(documentId, "v1"), repository.countChunks(documentId, "v1"));
    }

    // ------------------------------------------------------------------
    // 场景 3：版本更新与旧版本清理
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("场景3 更新：v2 先写后切换，v1 变 SUPERSEDED 且向量被清理")
    void newVersionSwitchesAndCleansOldVersion() {
        String documentId = PREFIX + "b";
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v1", 3, "旧版"));
        assertThat(pgCount(documentId, "v1")).isEqualTo(3);

        VectorIndexResultView v2 = indexService.indexChunks(documentId, "v2",
                "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v2", 3, "新版"));

        assertThat(v2.getStatus()).isEqualTo(VectorIndexStatus.COMPLETED.name());
        assertThat(pgCount(documentId, "v2")).isEqualTo(3);
        assertThat(pgCount(documentId, "v1")).isZero();
        assertThat(repository.countChunks(documentId, "v1")).isZero();
        assertThat(repository.findTask(documentId, "v1").orElseThrow().getStatus())
                .isEqualTo(VectorIndexStatus.SUPERSEDED.name());
        System.out.printf("[D31-IT] 场景3 版本切换通过：v2=%d v1=%d%n",
                pgCount(documentId, "v2"), pgCount(documentId, "v1"));
    }

    @Test
    @Order(5)
    @DisplayName("迟到任务：已被取代的旧版本回写被拒绝，不产生版本回滚")
    void staleVersionWriteIsRejected() {
        String documentId = PREFIX + "b";
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v1", 3, "旧版"));
        indexService.indexChunks(documentId, "v2", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v2", 3, "新版"));

        assertThatThrownBy(() -> indexService.indexChunks(documentId, "v1",
                "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v1", 3, "旧版")))
                .isInstanceOf(VectorIndexException.class)
                .hasMessageContaining("迟到写入");
        assertThat(repository.findTask(documentId, "v1").orElseThrow().getStatus())
                .isEqualTo(VectorIndexStatus.SUPERSEDED.name());
        System.out.println("[D31-IT] 迟到任务被拒绝，v1 仍为 SUPERSEDED");
    }

    // ------------------------------------------------------------------
    // 场景 4：按文档删除 + 重复删除
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    @DisplayName("场景4 删除：PG 与 MySQL 双向清理，重复删除不报错")
    void deleteByDocumentRemovesEverythingAndIsRepeatable() {
        String documentId = PREFIX + "c";
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v1", 3, "待删除"));

        indexService.deleteByDocument(documentId);
        assertThat(pgCount(documentId, "v1")).isZero();
        assertThat(repository.countChunks(documentId, "v1")).isZero();
        assertThat(repository.findTasksByDocument(documentId)).isEmpty();

        indexService.deleteByDocument(documentId);
        assertThat(pgCount(documentId, "v1")).isZero();
        System.out.println("[D31-IT] 场景4 重复删除通过（第二次执行无副作用）");
    }

    // ------------------------------------------------------------------
    // 场景 5：写入失败落账与重试
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("场景5 失败重试：第一批成功第二批中断 -> PARTIAL 落账 -> 重试恢复为 COMPLETED")
    void failedWriteIsRecordedAndRetryable() {
        String documentId = PREFIX + "d";
        EmbeddingModel failing = new FailingEmbeddingModel(embeddingModel, 2);
        PgVectorStore failingStore = buildStore(pgJdbc, failing);
        DocumentVectorIndexService failingService = newService(failingStore, pgVectorAccess);

        assertThatThrownBy(() -> failingService.indexChunks(documentId, "v1",
                "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v1", 3, "失败场景")))
                .isInstanceOf(VectorIndexException.class);

        VectorIndexTaskRow failedTask = repository.findTask(documentId, "v1").orElseThrow();
        assertThat(failedTask.getStatus()).isEqualTo(VectorIndexStatus.PARTIAL.name());
        assertThat(failedTask.getStage()).isEqualTo("EMBED_WRITE");
        assertThat(failedTask.getErrorCode()).isEqualTo("EMBED_WRITE_FAILED");
        assertThat(failedTask.getAttemptCount()).isEqualTo(1);
        assertThat(pgCount(documentId, "v1")).isLessThan(3);
        assertThat(repository.countChunks(documentId, "v1")).isEqualTo(3);
        System.out.printf("[D31-IT] 场景5 失败落账：status=%s stage=%s pg=%d%n",
                failedTask.getStatus(), failedTask.getStage(), pgCount(documentId, "v1"));

        List<VectorIndexResultView> recovered = indexService.retryFailedTasks();
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getStatus()).isEqualTo(VectorIndexStatus.COMPLETED.name());
        assertThat(pgCount(documentId, "v1")).isEqualTo(3);
        assertThat(repository.countChunksByStatus(documentId, "v1",
                ChunkRowStatus.INDEXED.name())).isEqualTo(3);
        System.out.printf("[D31-IT] 场景5 重试恢复：pg=%d indexed=%d%n",
                pgCount(documentId, "v1"),
                repository.countChunksByStatus(documentId, "v1", "INDEXED"));
    }

    // ------------------------------------------------------------------
    // 场景 6：连接失败（中断）后恢复
    // ------------------------------------------------------------------

    @Test
    @Order(8)
    @DisplayName("连接失败恢复：PG 不可达时任务 FAILED，恢复连接后重试成功且无重复")
    void connectionFailureIsRecoverable() {
        String documentId = PREFIX + "e";
        HikariConfig badConfig = new HikariConfig();
        badConfig.setJdbcUrl(env("D31_PG_URL",
                "jdbc:postgresql://192.168.200.153:5432/spring_ai"));
        badConfig.setUsername(env("D31_PG_USERNAME", "postgres"));
        badConfig.setPassword("d31-it-wrong-password");
        badConfig.setPoolName("d31-it-pg-bad");
        badConfig.setMaximumPoolSize(1);
        badConfig.setMinimumIdle(0);
        badConfig.setConnectionTimeout(3000);
        badConfig.setInitializationFailTimeout(-1);
        HikariDataSource badDataSource = new HikariDataSource(badConfig);
        try {
            PgVectorStore badStore = buildStore(new JdbcTemplate(badDataSource), embeddingModel);
            DocumentVectorIndexService badService = newService(badStore, pgVectorAccess);

            assertThatThrownBy(() -> badService.indexChunks(documentId, "v1",
                    "FIXED_LENGTH", "D31 集成测试", chunks(documentId, "v1", 3, "连接中断")))
                    .isInstanceOf(VectorIndexException.class);

            VectorIndexTaskRow task = repository.findTask(documentId, "v1").orElseThrow();
            assertThat(task.getStatus()).isEqualTo(VectorIndexStatus.FAILED.name());
            assertThat(pgCount(documentId, "v1")).isZero();
            assertThat(repository.countChunks(documentId, "v1")).isEqualTo(3);
            System.out.printf("[D31-IT] 连接失败落账：status=%s errorCode=%s%n",
                    task.getStatus(), task.getErrorCode());

            List<VectorIndexResultView> recovered = indexService.retryFailedTasks();
            assertThat(recovered).hasSize(1);
            assertThat(pgCount(documentId, "v1")).isEqualTo(3);
            assertThat(pgDistinctCount(documentId, "v1")).isEqualTo(3);
        } finally {
            badDataSource.close();
        }
        System.out.println("[D31-IT] 连接恢复后重试成功，向量无重复");
    }

    // ------------------------------------------------------------------
    // 额外检查：写入成功但状态更新失败（卡住任务恢复）
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    @DisplayName("卡住任务恢复：状态停留在 INDEXING 时按稳定 ID 幂等重跑，不新增重复行")
    void stuckTaskIsRecoveredIdempotently() {
        String documentId = PREFIX + "f";
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v1", 3, "卡住场景"));

        repository.updateTaskProgress(documentId, "v1", VectorIndexStatus.INDEXING.name(),
                "EMBED_WRITE", "", "", 1, null,
                LocalDateTime.now().minusMinutes(5));

        List<VectorIndexResultView> recovered = indexService.recoverStuckTasks();
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).getStatus()).isEqualTo(VectorIndexStatus.COMPLETED.name());
        assertThat(pgCount(documentId, "v1")).isEqualTo(3);
        assertThat(pgDistinctCount(documentId, "v1")).isEqualTo(3);
        System.out.println("[D31-IT] 卡住任务恢复通过，pg 行数仍为 3");
    }

    @Test
    @Order(10)
    @DisplayName("部分快照保护：快照不完整时不虚构重建，标记 FAILED + NO_COMPLETE_SNAPSHOT")
    void incompleteSnapshotIsNotRebuilt() {
        String documentId = PREFIX + "g";
        VectorIndexTaskRow task = new VectorIndexTaskRow();
        task.setDocumentId(documentId);
        task.setVersion("v1");
        task.setStrategy("FIXED_LENGTH");
        task.setContentHash("x".repeat(64));
        task.setSplitVersion(properties.getSplitVersion());
        task.setVectorSpaceId(descriptor.vectorSpaceId());
        task.setChunkCount(3);
        task.setStatus(VectorIndexStatus.INDEXING.name());
        task.setStage("EMBED_WRITE");
        task.setAttemptCount(1);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        task.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        repository.upsertTask(task);

        VectorChunkRow row = new VectorChunkRow();
        row.setDocumentId(documentId);
        row.setVersion("v1");
        row.setStrategy("FIXED_LENGTH");
        row.setChunkId("11111111-1111-5111-8111-111111111111");
        row.setChunkIndex(0);
        row.setContent("不完整快照的唯一一块");
        row.setChunkContentHash(new ContentHasher().hash("不完整快照的唯一一块"));
        row.setCharacterCount(10);
        row.setPageNumbers("1");
        row.setStatus(ChunkRowStatus.PENDING.name());
        row.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        row.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        repository.upsertChunk(row);

        List<VectorIndexResultView> recovered = indexService.recoverStuckTasks();
        assertThat(recovered).isEmpty();
        VectorIndexTaskRow failed = repository.findTask(documentId, "v1").orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(VectorIndexStatus.FAILED.name());
        assertThat(failed.getErrorCode()).isEqualTo("NO_COMPLETE_SNAPSHOT");
        System.out.println("[D31-IT] 不完整快照未被虚构重建，已标记 NO_COMPLETE_SNAPSHOT");
    }

    // ------------------------------------------------------------------
    // 场景 6（一致性）：孤儿 / 缺失 / 数量核对与补偿
    // ------------------------------------------------------------------

    @Test
    @Order(11)
    @DisplayName("跨库核对：dryRun 发现孤儿与缺失，补偿后再次核对归零")
    void reconciliationDetectsAndCompensates() {
        String documentId = PREFIX + "h";
        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                chunks(documentId, "v1", 3, "核对场景"));

        // 制造孤儿：PG 有一条不属于任何关联行的向量
        vectorStore.add(List.of(new Document("22222222-2222-5222-8222-222222222222",
                "孤儿向量", Map.of("documentId", PREFIX + "orphan", "version", "v1"))));
        // 制造缺失：删除一条真实向量的 PG 行，保留 MySQL 关联
        String missingId = repository.findChunks(documentId, "v1").get(0).getChunkId();
        pgJdbc.update("DELETE FROM public." + IT_TABLE + " WHERE id::text = ?", missingId);

        ReconciliationReportView dryRun = reconciliationService.reconcile(true);
        assertThat(dryRun.getOrphanVectors()).isNotEmpty();
        assertThat(dryRun.getMissingVectors()).isNotEmpty();
        assertThat(dryRun.getActionsTaken()).isEmpty();
        System.out.printf("[D31-IT] dryRun 核对：orphan=%d missing=%d mismatch=%d%n",
                dryRun.getOrphanVectors().size(), dryRun.getMissingVectors().size(),
                dryRun.getCountMismatches().size());

        ReconciliationReportView applied = reconciliationService.reconcile(false);
        assertThat(applied.getActionsTaken()).isNotEmpty();
        System.out.println("[D31-IT] 补偿动作: " + applied.getActionsTaken());

        ReconciliationReportView after = reconciliationService.reconcile(true);
        assertThat(after.getOrphanVectors()).isEmpty();
        assertThat(after.getMissingVectors()).isEmpty();
        assertThat(after.getCountMismatches()).isEmpty();
        assertThat(pgCount(documentId, "v1")).isEqualTo(3);
        assertThat(pgDistinctCount(documentId, "v1")).isEqualTo(3);
        System.out.println("[D31-IT] 补偿后核对归零，pg 行数=3");
    }

    // ------------------------------------------------------------------
    // 额外检查：并发导入
    // ------------------------------------------------------------------

    @Test
    @Order(12)
    @DisplayName("并发导入：两个线程同时写入同一文档版本，稳定 ID 保证最终无重复")
    void concurrentImportProducesNoDuplicates() throws Exception {
        String documentId = PREFIX + "i";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        indexService.indexChunks(documentId, "v1", "FIXED_LENGTH",
                                "D31 集成测试", chunks(documentId, "v1", 3, "并发场景"));
                        return "ok";
                    } catch (RuntimeException ex) {
                        return "failed:" + ex.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            System.out.println("[D31-IT] 并发结果: " + outcomes);

            // 允许并发冲突（如唯一键竞争导致一侧失败），随后重跑一次收敛
            indexService.indexChunks(documentId, "v1", "FIXED_LENGTH", "D31 集成测试",
                    chunks(documentId, "v1", 3, "并发场景"));

            assertThat(pgCount(documentId, "v1")).isEqualTo(3);
            assertThat(pgDistinctCount(documentId, "v1")).isEqualTo(3);
            assertThat(repository.countChunks(documentId, "v1")).isEqualTo(3);
            assertThat(repository.findTasksByDocument(documentId)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        System.out.println("[D31-IT] 并发导入收敛，无重复行");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static DocumentVectorIndexService newService(VectorStore store,
                                                          PgVectorAccess access) {
        return new DocumentVectorIndexService(store, access, repository,
                new StableChunkIdGenerator(), new ContentHasher(), properties, descriptor,
                null, new DataSourceTransactionManager(mysqlDataSource));
    }

    private static PgVectorStore buildStore(JdbcTemplate jdbcTemplate,
                                            EmbeddingModel model) {
        return PgVectorStore.builder(jdbcTemplate, model)
                .schemaName("public")
                .vectorTableName(IT_TABLE)
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(384)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .removeExistingVectorStoreTable(false)
                .initializeSchema(false)
                .build();
    }

    private static List<DocumentChunk> chunks(String documentId, String version,
                                              int count, String label) {
        List<DocumentChunk> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String content = "D31 集成测试 " + label + " 第 " + i + " 块：该文本用于验证向量入库、"
                    + "幂等、版本更新、失败恢复与跨库核对。编号 " + i + "。";
            list.add(new DocumentChunk(documentId, "D31 集成测试文档", version, i,
                    ChunkStrategy.FIXED_LENGTH, content,
                    content.codePointCount(0, content.length()), List.of(1),
                    List.of(new ChunkSource(1, i)), ChunkBoundaryType.SENTENCE));
        }
        return list;
    }

    private static int pgCount(String documentId, String version) {
        Integer count = pgJdbc.queryForObject("SELECT COUNT(*) FROM public." + IT_TABLE
                        + " WHERE metadata->>'documentId' = ? AND metadata->>'version' = ?",
                Integer.class, documentId, version);
        return count == null ? 0 : count;
    }

    private static int pgDistinctCount(String documentId, String version) {
        Integer count = pgJdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM public." + IT_TABLE
                        + " WHERE metadata->>'documentId' = ? AND metadata->>'version' = ?",
                Integer.class, documentId, version);
        return count == null ? 0 : count;
    }

    private static void cleanOwnRows() {
        if (mysqlJdbc != null) {
            mysqlJdbc.update("DELETE FROM vector_chunk_index WHERE document_id LIKE ?",
                    PREFIX + "%");
            mysqlJdbc.update("DELETE FROM vector_index_task WHERE document_id LIKE ?",
                    PREFIX + "%");
        }
        if (pgJdbc != null) {
            pgJdbc.update("DELETE FROM public." + IT_TABLE
                    + " WHERE metadata->>'documentId' LIKE ?", PREFIX + "%");
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
        try (InputStream input = VectorIndexRealIntegrationTest.class.getClassLoader()
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
            throw new IllegalStateException(
                    "D31_VECTOR_INDEX_IT=true 时必须提供环境变量 " + name + "（口令不得写入文件）");
        }
        return value;
    }

    /**
     * 在指定调用序号后模拟 Embedding 服务中断的包装模型。
     */
    private static final class FailingEmbeddingModel implements EmbeddingModel {

        private final EmbeddingModel delegate;

        private final int failFromCall;

        private final AtomicInteger calls = new AtomicInteger();

        private FailingEmbeddingModel(EmbeddingModel delegate, int failFromCall) {
            this.delegate = delegate;
            this.failFromCall = failFromCall;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (calls.incrementAndGet() >= failFromCall) {
                throw new IllegalStateException("模拟 Embedding 服务连接中断");
            }
            return delegate.call(request);
        }

        @Override
        public float[] embed(Document document) {
            if (calls.incrementAndGet() >= failFromCall) {
                throw new IllegalStateException("模拟 Embedding 服务连接中断");
            }
            return delegate.embed(document);
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }
    }
}

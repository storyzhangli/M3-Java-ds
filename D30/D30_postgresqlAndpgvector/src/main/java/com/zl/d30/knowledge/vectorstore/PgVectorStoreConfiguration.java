package com.zl.d30.knowledge.vectorstore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zl.d30.common.AiConfigurationException;
import com.zl.d30.knowledge.embedding.DistanceMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * D30 PostgreSQL/pgvector 向量存储装配（手动构造，双数据源隔离）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>PG 连接（HikariDataSource/JdbcTemplate）不以 Spring Bean 暴露：主数据源仍是 MySQL，
 *       新增第二个 DataSource Bean 会使 JdbcTemplateAutoConfiguration / MyBatis 自动配置
 *       因 {@code @ConditionalOnSingleCandidate(DataSource.class)} 整体退让，破坏现有业务；</li>
 *   <li>手动构造 PgVectorStore Bean（类型精确为 PgVectorStore），Spring AI 官方
 *       PgVectorStoreAutoConfiguration 因 {@code @ConditionalOnMissingBean} 自动退让，
 *       不会误用 MySQL JdbcTemplate 建向量表；</li>
 *   <li>装配前强制校验：配置维度必须与当前 EmbeddingModel（复用 D29
 *       LocalHashNgramEmbeddingModel，384 维）一致，距离度量仅允许 COSINE；</li>
 *   <li>initializeSchema / removeExistingVectorStoreTable 恒为 false，禁止应用启动
 *       建表或删除已有向量数据；</li>
 *   <li>配置类实现 DisposableBean，应用关闭时显式关闭 PG 连接池。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = true)
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreConfiguration.class);

    private HikariDataSource pgDataSource;
    private JdbcTemplate pgJdbcTemplate;

    @Bean
    public PgVectorStore pgVectorStore(PgVectorStoreProperties properties,
                                       EmbeddingModel embeddingModel) {
        properties.requireValid();
        if (properties.getDimensions() != embeddingModel.dimensions()) {
            throw new AiConfigurationException("pgvector 配置维度 " + properties.getDimensions()
                    + " 与当前 EmbeddingModel 维度 " + embeddingModel.dimensions()
                    + " 不一致，拒绝装配向量存储");
        }
        if (properties.getDistanceMetric() != DistanceMetric.COSINE) {
            throw new AiConfigurationException(
                    "D30 仅支持 COSINE 距离度量（vector_cosine_ops），当前配置: "
                            + properties.getDistanceMetric());
        }

        this.pgDataSource = createDataSource(properties);
        this.pgJdbcTemplate = new JdbcTemplate(this.pgDataSource);

        PgVectorStore store = PgVectorStore.builder(this.pgJdbcTemplate, embeddingModel)
                .schemaName(properties.getSchemaName())
                .vectorTableName(properties.getTableName())
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(properties.getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(mapIndexType(properties.getIndexType()))
                .removeExistingVectorStoreTable(properties.isRemoveExistingVectorStoreTable())
                .initializeSchema(properties.isInitializeSchema())
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .build();
        log.info("PgVectorStore 装配完成: table={}.{}, dimensions={}, distance=COSINE, index=HNSW, initializeSchema=false",
                properties.getSchemaName(), properties.getTableName(), properties.getDimensions());
        return store;
    }

    @Bean
    public PgVectorEnvironmentVerifier pgVectorEnvironmentVerifier(PgVectorStore pgVectorStore,
                                                                   PgVectorStoreProperties properties) {
        return new PgVectorEnvironmentVerifier(this.pgJdbcTemplate, properties);
    }

    /**
     * 启动完成后输出一次只读环境报告（连接失败时 Hikari fail-fast 已阻止启动，此处只记录详情）。
     */
    @Bean
    public ApplicationRunner pgVectorEnvironmentReportRunner(PgVectorEnvironmentVerifier verifier) {
        return args -> {
            PgVectorEnvironmentReport report = verifier.verify();
            if (report.healthy()) {
                log.info("pgvector 环境报告: healthy=true, table={}, embedding={}, extension={}, pg={}",
                        report.tableQualifiedName(), report.embeddingColumnType(),
                        report.vectorExtensionVersion(), report.pgVersion());
            } else {
                log.warn("pgvector 环境报告: healthy=false, problems={}", report.problems());
            }
        };
    }

    private HikariDataSource createDataSource(PgVectorStoreProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setPoolName("pgvector-pool");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        // fail-fast：连接池初始化 1 秒内拿不到连接则启动失败，不掩盖数据库不可用
        config.setInitializationFailTimeout(1_000);
        return new HikariDataSource(config);
    }

    private PgVectorStore.PgIndexType mapIndexType(PgVectorIndexType indexType) {
        return switch (indexType) {
            case NONE -> PgVectorStore.PgIndexType.NONE;
            case IVFFLAT -> PgVectorStore.PgIndexType.IVFFLAT;
            case HNSW -> PgVectorStore.PgIndexType.HNSW;
        };
    }

    @Override
    public void destroy() {
        if (this.pgDataSource != null) {
            this.pgDataSource.close();
            log.info("pgvector 连接池已关闭");
        }
    }
}

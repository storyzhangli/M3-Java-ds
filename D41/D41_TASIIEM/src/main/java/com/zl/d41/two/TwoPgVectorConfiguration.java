package com.zl.d41.two;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zl.d41.common.AiConfigurationException;
import com.zl.d41.access.AccessPolicyService;
import com.zl.d41.knowledge.KnowledgeCorpus;
import com.zl.d41.knowledge.chunking.KnowledgeDocumentChunkingService;
import com.zl.d41.knowledge.document.repository.KnowledgeDocumentRepository;
import com.zl.d41.two.BailianEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import com.zl.d41.knowledge.embedding.DistanceMetric;
import com.zl.d41.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d41.knowledge.embedding.EmbeddingProperties;
import com.zl.d41.knowledge.embedding.EmbeddingProvider;
import com.zl.d41.knowledge.retrieval.config.RetrievalProperties;
import com.zl.d41.knowledge.retrieval.service.QueryEmbeddingService;
import com.zl.d41.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d41.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d41.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d41.knowledge.vectorindex.support.ContentHasher;
import com.zl.d41.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d41.knowledge.vectorstore.PgVectorAccess;
import com.zl.d41.knowledge.vectorstore.PgVectorIndexType;
import com.zl.d41.knowledge.vectorstore.PgVectorStoreProperties;
import com.zl.d41.two.TwoCorpusIndexService;
import com.zl.d41.two.TwoSpaceComponents;
import com.zl.d41.two.TwoVectorChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * two_ 隔离链路装配（方案 B：手动构造，零同类型第二 Bean）。
 *
 * <p>与本地 LOCAL 链路的关系：</p>
 * <ul>
 *   <li>独立配置前缀 {@code app.embedding.two} 与 {@code app.vectorstore.two-pgvector}，
 *       经 {@link Binder} 绑定为<b>普通对象</b>（不注册 @ConfigurationProperties Bean），
 *       本地 {@code EmbeddingModelConfiguration} 的 LOCAL 门禁与既有装配完全不动；</li>
 *   <li>two_PgVectorStore 用 {@link BailianEmbeddingModel} 构造（embedding 发生在
 *       PgVectorStore.add()/similaritySearch() 内部），表名强制 two_ 前缀；</li>
 *   <li>入库/检索编排复用既有 {@link DocumentVectorIndexService} /
 *       {@link SimilarityRetrievalService} / {@link QueryEmbeddingService} 类，
 *       仅替换依赖实例（two_ 存储、two_ 持久层、two_ 描述符），不复制业务逻辑；</li>
 *   <li>MySQL 主数据源 JdbcTemplate 与事务管理器共享（two_ 表与旧表同库不同表）；
 *       PG 连接池独立（two-pgvector-pool，max 2），不暴露 DataSource/JdbcTemplate Bean；</li>
 *   <li>装配开关 {@code app.vectorstore.two-pgvector.enabled} 默认 false：
 *       主配置显式开启；test/experiment profile 显式关闭，历史上下文零影响。</li>
 * </ul>
 *
 * <p>fail-fast 校验：provider 必须 BAILIAN、keyRequired 时密钥非空、
 * 配置维度与模型维度一致、仅 COSINE、HNSW 维度上限 2000、表名必须 two_ 前缀、
 * initializeSchema/removeExisting 恒 false。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.vectorstore.two-pgvector.enabled", havingValue = "true",
        matchIfMissing = false)
public class TwoPgVectorConfiguration implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TwoPgVectorConfiguration.class);

    /** pgvector HNSW 索引对 vector 类型的维度上限（探针冻结：1024 ≤ 2000，可建）。 */
    private static final int HNSW_MAX_DIMENSIONS = 2000;

    private HikariDataSource twoDataSource;

    @Bean
    public TwoSpaceComponents twoSpaceComponents(Environment environment,
                                                 JdbcTemplate mysqlJdbcTemplate,
                                                 StableChunkIdGenerator idGenerator,
                                                 ContentHasher hasher,
                                                 VectorIndexProperties vectorIndexProperties,
                                                 RetrievalProperties retrievalProperties,
                                                 KnowledgeCorpus knowledgeCorpus,
                                                 KnowledgeDocumentChunkingService chunkingService,
                                                 PlatformTransactionManager transactionManager,
                                                 // 权限三键读取端口 + 过滤策略（access 关闭时 provider 为 null → 不过滤，零差异）
                                                 KnowledgeDocumentRepository documentMetaRepository,
                                                 ObjectProvider<AccessPolicyService> accessPolicyProvider) {
        EmbeddingProperties embeddingProperties = Binder.get(environment)
                .bind("app.embedding.two", Bindable.of(EmbeddingProperties.class))
                .orElseGet(EmbeddingProperties::new);
        PgVectorStoreProperties pgProperties = Binder.get(environment)
                .bind("app.vectorstore.two-pgvector", Bindable.of(PgVectorStoreProperties.class))
                .orElseGet(PgVectorStoreProperties::new);

        validate(embeddingProperties, pgProperties);
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(embeddingProperties);

        int apiBatchSize = environment.getProperty(
                "app.embedding.two.api-batch-size", Integer.class, 10);
        String ledgerPath = environment.getProperty(
                "app.embedding.two.ledger-path", "");
        BailianEmbeddingModel embeddingModel = new BailianEmbeddingModel(
                descriptor, embeddingProperties.getApiKey(), apiBatchSize, ledgerPath);

        this.twoDataSource = createDataSource(pgProperties);
        JdbcTemplate twoJdbcTemplate = new JdbcTemplate(this.twoDataSource);

        PgVectorStore twoStore = PgVectorStore.builder(twoJdbcTemplate, embeddingModel)
                .schemaName(pgProperties.getSchemaName())
                .vectorTableName(pgProperties.getTableName())
                .idType(PgVectorStore.PgIdType.UUID)
                .dimensions(pgProperties.getDimensions())
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(mapIndexType(pgProperties.getIndexType()))
                .removeExistingVectorStoreTable(false)
                .initializeSchema(false)
                .maxDocumentBatchSize(pgProperties.getMaxDocumentBatchSize())
                .build();

        PgVectorAccess twoAccess = new PgVectorAccess(twoJdbcTemplate, pgProperties);
        TwoVectorChunkRepository twoRepository = new TwoVectorChunkRepository(mysqlJdbcTemplate);

        DocumentVectorIndexService twoIndexService = new DocumentVectorIndexService(
                twoStore, twoAccess, twoRepository, idGenerator, hasher,
                vectorIndexProperties, descriptor, chunkingService, transactionManager,
                documentMetaRepository);
        // R02-06：two_ 手动装配显式声明走 legacy 兼容通道（不进 d41_document_sync；
        // two_ 表前缀隔离，事件由 TwoCorpusIndexService 自管）。生产 Spring 装配默认
        // syncStateRequired=true，syncService 缺席时删除入口 fail-fast。
        twoIndexService.setSyncStateRequired(false);
        QueryEmbeddingService twoQueryEmbeddingService = new QueryEmbeddingService(
                embeddingModel, descriptor, retrievalProperties);
        SimilarityRetrievalService twoRetrievalService = new SimilarityRetrievalService(
                twoStore, twoQueryEmbeddingService, retrievalProperties, accessPolicyProvider);
        TwoCorpusIndexService twoCorpusIndexService = new TwoCorpusIndexService(
                knowledgeCorpus, twoIndexService);

        log.info("two_ 向量空间装配完成: provider={}, model={}, dimensions={}, vectorSpaceId={}, "
                        + "table={}.{}, indexVersion={}, apiBatchSize={}",
                descriptor.provider(), descriptor.modelName(), descriptor.dimensions(),
                descriptor.vectorSpaceId(), pgProperties.getSchemaName(),
                pgProperties.getTableName(), descriptor.indexVersion(), apiBatchSize);
        return new TwoSpaceComponents(descriptor, twoIndexService, twoRetrievalService,
                twoCorpusIndexService, twoAccess);
    }

    private void validate(EmbeddingProperties embeddingProperties,
                          PgVectorStoreProperties pgProperties) {
        embeddingProperties.requireValid();
        pgProperties.requireValid();
        if (embeddingProperties.getProvider() != EmbeddingProvider.BAILIAN) {
            throw new AiConfigurationException(
                    "two_ 链路 provider 必须为 BAILIAN，当前: " + embeddingProperties.getProvider());
        }
        if (embeddingProperties.isKeyRequired()
                && (embeddingProperties.getApiKey() == null
                || embeddingProperties.getApiKey().isBlank())) {
            throw new AiConfigurationException(
                    "百炼 API Key 为空：必须通过环境变量 DASHSCOPE_API_KEY 注入（fail-fast，禁止空密钥启动）");
        }
        if (pgProperties.getDimensions() != embeddingProperties.getDimensions()) {
            throw new AiConfigurationException("two_ pgvector 配置维度 " + pgProperties.getDimensions()
                    + " 与百炼 Embedding 配置维度 " + embeddingProperties.getDimensions()
                    + " 不一致，拒绝装配（维度以探针实测冻结值为准）");
        }
        if (pgProperties.getDistanceMetric() != DistanceMetric.COSINE) {
            throw new AiConfigurationException(
                    "two_ 链路仅支持 COSINE 距离度量，当前: " + pgProperties.getDistanceMetric());
        }
        if (pgProperties.getIndexType() == PgVectorIndexType.HNSW
                && pgProperties.getDimensions() > HNSW_MAX_DIMENSIONS) {
            throw new AiConfigurationException("two_ HNSW 索引维度上限 " + HNSW_MAX_DIMENSIONS
                    + "，当前 " + pgProperties.getDimensions() + "，请改用 NONE/IVFFLAT 或降低维度");
        }
        if (!pgProperties.getTableName().startsWith("two_")) {
            throw new AiConfigurationException(
                    "two_ 链路表名必须以 two_ 前缀隔离，当前: " + pgProperties.getTableName()
                            + "（禁止写入本地链路 vector_store）");
        }
    }

    private HikariDataSource createDataSource(PgVectorStoreProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setPoolName("two-pgvector-pool");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
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
        if (this.twoDataSource != null) {
            this.twoDataSource.close();
            log.info("two_ pgvector 连接池已关闭");
        }
    }
}
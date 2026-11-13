# D30 证据 01：仓库扫描与现状分析

- 生成时间：2026-11-13（Phase 1）
- 项目根：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D30`
- 骨架模块：`D30\D30_postgresqlAndpgvector`

## 1. 向量存储现状判定：状态 A（无任何现成向量存储）

全仓 Grep 关键词 `VectorStore|PgVectorStore|pgvector|vector_store|vector-cosine|vector_cosine_ops|spring-ai-starter-vector-store|pgjdbc|postgresql`：
仅命中两个 pom.xml，且命中原因与 pgvector 无关。

- 根 POM（`D30\pom.xml`）：spring-ai-bom 1.1.8 / pdfbox 3.0.7 / spring-cloud 2025.0.0 /
  spring-cloud-alibaba 2025.0.0.0 / mybatis-plus 3.5.17，均无 pgvector starter、无 pgjdbc。
- 模块 POM（`D30_postgresqlAndpgvector\pom.xml`）：业务数据源为 `mysql-connector-java 8.0.33`；
  文档读取依赖齐全（markdown/pdf/jsoup/tika），**无 spring-ai-starter-vector-store-pgvector、无 org.postgresql:postgresql**。

结论：按任务书规则，采用 PostgreSQL + pgvector（虚拟机 192.168.200.153）作为唯一向量库。

## 2. 可复用资产（D29 快照已随 D30 骨架就位）

| 组件 | 位置 | 事实 |
|---|---|---|
| LocalHashNgramEmbeddingModel | `knowledge/embedding` | 实现 Spring AI `EmbeddingModel`，确定性字符/词 n-gram 哈希，384 维，输出 L2 归一化向量；**非预训练神经网络** |
| EmbeddingVectorValidator | `knowledge/embedding` | 空向量 / 维度不一致 / NaN-Infinity / 零向量校验，抛 `EmbeddingException(EMBEDDING_RESPONSE_INVALID)` |
| EmbeddingProperties | `knowledge/embedding` | 前缀 `app.embedding`，dimensions=384、provider=LOCAL、distanceMetric=COSINE、indexVersion=d30-local-384-v1 |
| EmbeddingModelConfiguration | `knowledge/embedding` | 仅装配 LOCAL provider，Bean = `EmbeddingModel` |
| EmbeddingModelDescriptor / VectorSpaceIdentity | `knowledge/embedding` | 向量空间唯一标识（SHA-256 前缀 vs_） |
| IndexCompatibilityValidator / VectorIndexMetadata | `knowledge/embedding` | 向量空间与维度兼容契约 |
| ChunkEmbeddingService | `knowledge/embedding` | Chunk→Embedding 批处理，写入前调用 validator 校验维度 |
| Chunking / Document 导入全套 | `knowledge/chunking`、`knowledge/document` | D29 快照完整保留 |

## 3. Git 状态（开始前）

- 分支：`master`
- 工作区：仅 1 个未跟踪文件（用户上传的任务书副本，位于 docs/proj_docs 下）
- 最近提交：`7d57acb D30 项目基础骨架`

## 4. Spring AI 1.1.8 PgVectorStore API 反查（本地仓库 jar，javap 实测）

- 构件名：`spring-ai-pgvector-store`（starter 为 `spring-ai-starter-vector-store-pgvector`）
- 仅 Builder 构造：`PgVectorStore.builder(JdbcTemplate, EmbeddingModel)`
- Builder 方法：schemaName / vectorTableName / idType(UUID|TEXT|INTEGER|SERIAL|BIGSERIAL) /
  dimensions / distanceType(COSINE_DISTANCE|EUCLIDEAN_DISTANCE|NEGATIVE_INNER_PRODUCT) /
  indexType(NONE|IVFFLAT|HNSW) / removeExistingVectorStoreTable / initializeSchema / maxDocumentBatchSize
- 自动配置 `PgVectorStoreAutoConfiguration` 带 `@ConditionalOnMissingBean`，手动定义同类型 Bean 即接管
- `VectorStore` 接口 1.1.8 仅 `add(List<Document>)`（无 write(Document,float[])），写入时由注入的
  EmbeddingModel 重新生成向量（本模型确定性，与 D29 输出一致）
- `SearchRequest.builder().query().topK().similarityThreshold()`
- `Document(String id, String text, Map metadata)` / `getScore()` / builder 支持 `score(Double)`

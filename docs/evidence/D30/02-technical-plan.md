# D30 证据 02：技术方案

- 生成时间：2026-11-13（Phase 2）

## 方案要点

1. **依赖**：`spring-ai-starter-vector-store-pgvector`（版本由 spring-ai-bom 1.1.8 治理）+
   `org.postgresql:postgresql`（版本由 Spring Boot 3.5.16 治理）。仅加这两个依赖。
2. **双数据源隔离**：主数据源 MySQL 不变；PG 连接（HikariDataSource + JdbcTemplate）**不以 Spring
   Bean 暴露**（若出现第二个 DataSource Bean，JdbcTemplateAutoConfiguration / MyBatis 自动配置会因
   `@ConditionalOnSingleCandidate(DataSource.class)` 整体退让，破坏现有业务），改由配置类内部持有并
   显式关闭。
3. **手动构造 PgVectorStore Bean**：类型精确为 PgVectorStore，官方自动配置因
   `@ConditionalOnMissingBean` 退让，不会误用 MySQL JdbcTemplate。
4. **装配守卫**：配置维度 ≠ EmbeddingModel 维度（384）→ 拒绝装配；非 COSINE → 拒绝装配。
5. **DDL 策略**：`initialize-schema=false` + `remove-existing-vector-store-table=false`；
   幂等 DDL 脚本文档化（`docs/temp_docs/D30/pgvector-schema-idempotent.sql`），不自动执行。
6. **环境检查**：`PgVectorEnvironmentVerifier` 只读 SQL 验证 连通性/扩展版本/表存在/embedding 列
   `vector(384)`/HNSW+vector_cosine_ops 索引，结果结构化报告 + 启动日志。
7. **服务层**：`KnowledgeVectorStoreService.storeChunks(List<DocumentChunk>)` 构建带 UUID id 与溯源
   metadata 的 Document 后 `vectorStore.add()`；`search(query, topK, threshold)` 走 COSINE
   similaritySearch，返回 `VectorSearchHit`（documentId/chunkIndex/title/score/片段）。
8. **测试策略**：
   - 单元测试（无 DB）：配置校验、装配守卫、环境检查逻辑（Mock JdbcTemplate）、服务映射；
   - 真实集成测试 `PgVectorStoreRealIntegrationTest`：环境变量 `D30_PG_INTEGRATION=true` 门控，
     真连 192.168.200.153，独立 smoke 表 `d30_vector_smoke_test`，只清理 `d30TestMarker=true`
     标记行，表与索引保留为证据；
   - 测试环境回归保护：`app.vectorstore.pgvector.enabled` 默认 true（生产 fail-fast），
     application-test.yml 显式 false，历史 @SpringBootTest 不受 PG 凭据影响。
9. **凭据**：`PG_VECTOR_URL / PG_VECTOR_USERNAME / PG_VECTOR_PASSWORD` 环境变量注入，密码不落库、
   不入日志、不提交 Git。

## 表与索引

| 对象 | 定义 |
|---|---|
| 主表（已存在，Spring AI 默认 schema） | `public.vector_store(id uuid PK, content text, metadata json, embedding vector(384))` |
| 主索引（已存在） | `spring_ai_vector_index USING hnsw (embedding vector_cosine_ops)` |
| 集成测试 smoke 表 | `public.d30_vector_smoke_test`（同结构，HNSW cosine 索引） |

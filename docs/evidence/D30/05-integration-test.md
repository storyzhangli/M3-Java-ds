# D30 证据 05：真实 pgvector 集成测试（L3/L4/L5）

- 命令：`mvn test -Dtest=PgVectorStoreRealIntegrationTest`
- 门控：`D30_PG_INTEGRATION=true` + `D30_PG_URL / D30_PG_USERNAME / D30_PG_PASSWORD`（环境变量，未落盘）
- 日志：`logs/D30/integration-test-run2.log`
- 结果：**Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS**

## 关键输出（真实数据）

1. **L3 环境报告**（healthy=true）：
   `vectorExtensionVersion=0.8.7, tableExists=true, embeddingColumnType=vector(384),
   embeddingColumnDimensions=384, indexDefinitions=[...USING hnsw (embedding vector_cosine_ops)...]`
2. **L4 真实写入**：5 条 384 维向量写入 smoke 表，`COUNT(*)=5` 复核通过。
3. **L5 COSINE 检索**：
   - query=`压缩机启动没有反应` → top1 docId=doc-001，score=0.1462194323539734 > top2 score=0.04456675052642822；
   - 同文本自查询 score=**1.0**（COSINE 语义正确）；
   - 改写查询 `轴承异响需要润滑` → top1 docId=doc-003，score=0.2909855246543884 ≥ top2 score=0.0。
4. **维度防护**：3 维向量写入 vector(384) 列被 PostgreSQL 直接拒绝（DataAccessException）。

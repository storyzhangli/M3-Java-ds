# D30 证据 06：主应用真实启动验证（L2，主应用级）

- 命令：`mvn -pl D30_postgresqlAndpgvector spring-boot:run`（环境变量注入 PG 凭据与 AI_MODE=mock）
- 日志：`logs/D30/app-startup.log`

## 关键启动日志（节选，真实输出）

```
pgvector-pool - Start completed.
PgVectorStore - Using the vector table name: vector_store. Is empty: false
PgVectorStoreConfiguration - PgVectorStore 装配完成: table=public.vector_store,
    dimensions=384, distance=COSINE, index=HNSW, initializeSchema=false
Tomcat started on port 8080 (http) with context path '/'
Started D30SpringAiApplication in 11.438 seconds
PgVectorEnvironmentVerifier - pgvector 环境检查通过: table=public.vector_store,
    embedding=vector(384), extension=0.8.7, pg=PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2)
```

结论：完整 Bean 图装配成功，双数据源（MySQL 业务 + PG 向量库）共存无冲突，
启动后环境报告 healthy；验证完毕后应用已正常停止。

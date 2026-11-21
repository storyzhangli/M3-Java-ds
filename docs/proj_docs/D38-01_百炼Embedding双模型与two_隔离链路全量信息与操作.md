# D38-01 百炼 Embedding 双模型与 two_ 隔离链路 — 全量信息与操作

> 模块：M3-Java-ds / D38（D38_QWE_MROQRE）｜迭代：D38-R01｜日期：2026-11-21
> 技术基线：JDK 21 · Spring Boot 3.5.16 · Spring AI 1.1.8 · PostgreSQL 18.7 + pgvector 0.8.7 · MySQL 8（ai_db）
> 配套文档：《D38-02_百炼Embedding双模型与two_隔离链路技术文档与实践指南.md》
> 状态文件：`docs\evidence\D38\iteration_records\D38_state_R01.md`

## 一、本轮做了什么（一句话）

在**完全保留本地哈希 Embedding（LOCAL，384 维）链路**的前提下，新增阿里百炼 `qwen3.7-text-embedding`（1024 维，探针实测冻结）作为第二 Embedding 模型，以 `two_` 前缀全套新表、新装配、新端点建立平行链路，真实闭环验证通过（冒烟 6/6、幂等复跑 0 调用、调用预算 17/18）。

## 二、双链路全景对照

| 维度 | LOCAL 链路（不动） | two_ 链路（新增） |
|---|---|---|
| Embedding 模型 | LocalHashNgramEmbeddingModel（确定性哈希 n-gram） | BailianEmbeddingModel（百炼 compatible-mode HTTP） |
| 模型名 / 维度 | local-hash-ngram-v1 / 384 | qwen3.7-text-embedding / 1024（探针冻结） |
| 向量空间 ID | vs_（LOCAL\|local-hash-ngram-v1\|2026.11\|384） | vs_3a789450fd0d86601065c98057ac1adb |
| indexVersion | d38-local-384-v1 | two-bailian-1024-v1 |
| PG 向量表 | public.vector_store vector(384) | public.two_vector_store vector(1024) |
| PG 索引 | spring_ai_vector_index（HNSW） | two_vector_store_hnsw_idx（HNSW cosine） |
| MySQL 状态表 | vector_index_task / vector_chunk_index | two_vector_index_task / two_vector_chunk_index |
| 配置前缀 | app.embedding / app.vectorstore.pgvector | app.embedding.two / app.vectorstore.two-pgvector |
| 入库端点 | POST /api/v1/knowledge/retrieval/corpus/index | POST /api/v1/two/knowledge/retrieval/corpus/index |
| 检索端点 | POST /api/v1/knowledge/retrieval/search | POST /api/v1/two/knowledge/retrieval/search |
| 装配开关 | app.vectorstore.pgvector.enabled（默认 true） | app.vectorstore.two-pgvector.enabled（主配置 true，test/experiment false） |
| 费用 | 0 | 按 token 计费（本轮全迭代 853 tokens / 17 次调用） |
| 密钥 | 无需 | DASHSCOPE_API_KEY（环境变量，fail-fast） |

## 三、新增与修改文件全清单

### 3.1 新增 main 代码（包 `com.zl.d38.two`，6 类）

| 类 | 职责 | 是否 Spring Bean |
|---|---|---|
| BailianEmbeddingModel | 百炼 Embedding 适配器：JDK HttpClient + Jackson，≤10 切批、429/5xx 重试 1 次、401/403 不重试、连败 2 次熔断、维度/范数校验、JSONL 调用台账 | 否（手动构造） |
| TwoPgVectorConfiguration | two_ 装配：Binder 绑定独立配置、fail-fast 校验、独立 Hikari 池（two-pgvector-pool）、构造 two_PgVectorStore 与全部编排服务 | 是（@Configuration，门禁开关） |
| TwoSpaceComponents | two_ 组件持有器 record（descriptor/indexService/retrievalService/corpusIndexService/pgVectorAccess） | 是（唯一业务入口 Bean） |
| TwoVectorChunkRepository | two_ MySQL 持久层：继承 VectorChunkRepository 覆写全部公开方法，SQL 全指向 two_ 表 | 否 |
| TwoCorpusIndexService | two_ 语料入库编排（与本地 CorpusIndexService 同构转换，委托 two_ indexService） | 否 |
| TwoKnowledgeRetrievalController | 新端点 /api/v1/two/knowledge/retrieval/{corpus/index,search} | 是（@RestController，同门禁） |

### 3.2 新增测试 / DDL / 工具

- `src/test/java/com/zl/d38/two/BailianEmbeddingModelTest.java` — 11 个零计费桩用例（JDK HttpServer 夹具）
- `docs/temp_docs/D38/two-pgvector-schema-idempotent.sql` — PG 幂等 DDL
- `docs/temp_docs/D38/two-schema-vector-index.sql` — MySQL 幂等 DDL
- `docs/temp_docs/D38/ExecuteTwoDdl.java` — JDBC DDL 执行/计数工具（本机无 mysql CLI）
- `docs/temp_docs/D38/run-two-verification.ps1` — 真实闭环验证脚本

### 3.3 既有文件修改（5 个，+79/-1，git diff 可审计）

| 文件 | 改动 |
|---|---|
| EmbeddingProvider.java | +BAILIAN 枚举值（全仓无 provider switch 穷举，编译安全） |
| EmbeddingModelCatalog.java | +BAILIAN_QWEN37_TEXT_EMBEDDING 候选元数据（价格 null，不参与计费结论） |
| application.yml | +app.embedding.two、+app.vectorstore.two-pgvector、access-log 排除 /api/v1/two/** |
| application-test.yml | two-pgvector.enabled: false（历史测试上下文零影响） |
| application-experiment.yml | two-pgvector.enabled: false（对照实验不混入百炼） |

**零改动承诺兑现**：PgVectorStoreConfiguration、EmbeddingModelConfiguration、DocumentVectorIndexService、SimilarityRetrievalService、QueryEmbeddingService、VectorChunkRepository、KnowledgeRetrievalController、旧表 DDL 全部未触碰；无需 @Primary（two_ 不注册第二个同类型 Bean）。

## 四、配置参考（application.yml 新增段）

```yaml
app:
  embedding:
    two:
      provider: ${TWO_EMBEDDING_PROVIDER:BAILIAN}
      model-name: ${TWO_EMBEDDING_MODEL:qwen3.7-text-embedding}
      model-version: ${TWO_EMBEDDING_MODEL_VERSION:current}   # 服务端无版本回显，登记假设
      dimensions: ${TWO_EMBEDDING_DIMENSIONS:1024}            # 探针实测冻结，禁止假设
      max-input-length: ${TWO_EMBEDDING_MAX_INPUT_LENGTH:8192}
      length-unit: ${TWO_EMBEDDING_LENGTH_UNIT:TOKENS}
      distance-metric: ${TWO_EMBEDDING_DISTANCE_METRIC:COSINE}
      index-version: ${TWO_EMBEDDING_INDEX_VERSION:two-bailian-1024-v1}
      api-url: ${BAILIAN_EMBEDDING_BASE_URL:}                 # 已含 /compatible-mode/v1
      api-key: ${DASHSCOPE_API_KEY:}                          # 仅环境变量注入
      key-required: ${TWO_EMBEDDING_KEY_REQUIRED:true}
      api-batch-size: ${TWO_EMBEDDING_API_BATCH_SIZE:10}      # 探针冻结上限 10
      ledger-path: ${TWO_EMBEDDING_LEDGER_PATH:}              # 调用台账 JSONL 路径
  vectorstore:
    two-pgvector:
      enabled: ${TWO_PGVECTOR_ENABLED:true}
      url: ${PG_VECTOR_URL:jdbc:postgresql://192.168.200.153:5432/spring_ai}
      username: ${PG_VECTOR_USERNAME:postgres}
      password: ${PG_VECTOR_PASSWORD:}                        # 无默认值，缺失即 fail-fast
      table-name: ${TWO_PG_VECTOR_TABLE:two_vector_store}     # 强制 two_ 前缀（代码校验）
      dimensions: ${TWO_PG_VECTOR_DIMENSIONS:1024}
      index-type: ${TWO_PG_VECTOR_INDEX_TYPE:HNSW}            # 1024 ≤ 2000 上限
      initialize-schema: false                                # 恒 false，外部 DDL 管理
      remove-existing-vector-store-table: false               # 恒 false
```

## 五、操作手册（可复现步骤）

### 5.1 前置条件

- 环境变量（Windows 用户级，只验存在不打印值）：`DASHSCOPE_API_KEY`、`BAILIAN_EMBEDDING_BASE_URL`（= `https://ws-b5h7p6oc83qc6dn5.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`）、`PG_VECTOR_PASSWORD`；MySQL 用 `MYSQL_PASSWORD`（本机默认 123456）。
- PG（192.168.200.153:5432/spring_ai）与 MySQL（127.0.0.1:3306/ai_db）可达；Redis 不可达不阻塞启动（懒连接）。
- Maven：`D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd`；JDK：`D:\SoftwareInstall\JDK\JDK21`；本地仓库：`D:\SoftwareInstall\Apache\repository`。

### 5.2 执行 two_ 幂等 DDL（可重复执行）

```powershell
# PG（psql 已装：E:\SoftwareInstall\AI\PostgreSQL\18\bin）
$env:PGPASSWORD = [Environment]::GetEnvironmentVariable("PG_VECTOR_PASSWORD","User")
& "E:\SoftwareInstall\AI\PostgreSQL\18\bin\psql.exe" -h 192.168.200.153 -p 5432 -U postgres -d spring_ai `
  -v ON_ERROR_STOP=1 -f "docs\temp_docs\D38\two-pgvector-schema-idempotent.sql"
$env:PGPASSWORD = $null

# MySQL（本机无 mysql CLI，经 JDBC 单文件工具）
& "D:\SoftwareInstall\JDK\JDK21\bin\java.exe" `
  -cp "D:\SoftwareInstall\Apache\repository\com\mysql\mysql-connector-j\9.7.0\mysql-connector-j-9.7.0.jar" `
  "docs\temp_docs\D38\ExecuteTwoDdl.java" `
  "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" `
  "root" "MYSQL_PASSWORD" "docs\temp_docs\D38\two-schema-vector-index.sql"
```

### 5.3 编译 / 测试 / 打包

```powershell
$env:JAVA_HOME = "D:\SoftwareInstall\JDK\JDK21"
$mvn = "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd"
& $mvn -B -o -f D38\pom.xml test          # 11 桩测试，零计费（test profile 关闭 two_）
& $mvn -B -o -f D38\pom.xml clean package -DskipTests
```

### 5.4 启动应用（环境变量会话注入，禁写文件/PATH）

```powershell
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable("PG_VECTOR_PASSWORD","User")
$env:DASHSCOPE_API_KEY = [Environment]::GetEnvironmentVariable("DASHSCOPE_API_KEY","User")
$env:BAILIAN_EMBEDDING_BASE_URL = [Environment]::GetEnvironmentVariable("BAILIAN_EMBEDDING_BASE_URL","User")
$env:AI_MODE = "mock"
$env:TWO_EMBEDDING_LEDGER_PATH = "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D38\calls\call-ledger.jsonl"
& "D:\SoftwareInstall\JDK\JDK21\bin\java.exe" -jar "D38\D38_QWE_MROQRE\target\D38_QWE_MROQRE-1.0-SNAPSHOT.jar"
```

启动成功标志（日志）：`two_ 向量空间装配完成: provider=BAILIAN, model=qwen3.7-text-embedding, dimensions=1024, vectorSpaceId=vs_3a789450fd0d86601065c98057ac1adb` 与 `Started D38SpringAiApplication`。

### 5.5 真实闭环验证

```powershell
& "docs\temp_docs\D38\run-two-verification.ps1"
```

脚本顺序：two_ 入库 → 幂等复跑（断言 0 API 调用）→ 6 问冒烟（QA-001/QA-004/QU-001/QU-003/QT-001/QF-001，取自 D37 冻结问题集）→ 本地回归（search/answer 各 1 次对照 D37 质量字段）→ 台账预算审计 → 密钥扫描 → 基线落盘 `docs\evidence\D38\baseline\v1.0\TWO_BAILIAN\<时间戳>\`。

### 5.6 端点契约（与本地链路同 DTO）

```powershell
# two_ 入库（幂等）
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/two/knowledge/retrieval/corpus/index?version=corpus-v1"
# two_ 检索
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/v1/two/knowledge/retrieval/search" `
  -ContentType "application/json; charset=utf-8" `
  -Body ([Text.Encoding]::UTF8.GetBytes('{"query":"数控机床主轴过热报警第一步检查什么","topK":5}'))
```

响应 `Result{code,msg,data}`；检索 data 含 hits（rank/chunkId/documentId/version/chunkIndex/score/distance…）、queryEmbeddingSummary（dimensions/vectorSpaceId/modelName/elapsedMillis）。score 语义 COSINE_SIMILARITY（越高越相似），阈值默认 0.0。

## 六、本轮真实验证结果（已执行并通过）

| 项 | 结果 |
|---|---|
| surefire 桩测试 | 11/11 PASS（21.6s，零计费） |
| 打包 | BUILD SUCCESS（45.7s） |
| DDL 幂等重跑 | PG/MySQL 各 2 次，0 报错 0 数据变化 |
| 应用启动 | 14.083s；two_ 装配成功；本地 vector(384) healthy |
| two_ 入库 | 3 文档 8 片段；two_vector_store=8；任务 3 COMPLETED；Chunk 8 INDEXED |
| 幂等复跑 | idempotent 全命中、processed=0、**0 API 调用** |
| 冒烟 | 6/6 PASS（QA-001 topScore 0.8668 / QA-004 0.8146 / QT-001 0.7515；QU-001/QU-003/QF-001 availability-only） |
| 本地回归 | QA-001 documentHit/chunkHit/decision=ANSWER 与 D37 PGVECTOR 基线一致 |
| 调用预算 | 17/18（探针 2 + 入库 3 + 检索 12），853 tokens，余 1 |
| 密钥扫描 | 基线 JSON + ledger + 应用日志 0 命中 |
| git 审计 | 既有 5 文件 +79/-1，其余全新增；未 commit/push |

## 七、证据索引

```text
docs\evidence\D38\
├── probe\embedding-probe.json            # P1 单条探针（200/1024/norm1/18tokens/563ms）
├── probe\embedding-probe-batch.json      # P2 批量探针（indexes 0,1/40tokens/623ms）
├── probe\two-space-manifest.json         # 冻结清单（模型/维度/空间/表名/预算）
├── calls\call-ledger.jsonl               # 17 条调用台账（seq/purpose/status/tokens/ms/batch）
├── baseline\v1.0\TWO_BAILIAN\20261121-185541\
│   ├── 00-env-check.json … 06-local-regression.json
│   ├── smoke-QA-001.json … smoke-QF-001.json
│   ├── summary.json / summary.md
└── iteration_records\D38_state_R01.md    # L1-L5 / C01-C16 逐条验收
logs\D38\D38-mvn-test-R01.log / D38-mvn-package-R01.log / D38-app-R01.log
```

## 八、注意事项与已知边界

1. **检索双重 Embedding**：每次 two_ 检索消耗 2 次 API 调用（查询预向量化 + PgVectorStore 内部再向量化，Spring AI 1.1.8 SearchRequest 只接受文本）。预算规划必须按 2× 计。
2. **入库按文档分批**：8 片段分属 3 文档 → 3 次 HTTP（非 1 批），编排语义使然。
3. **分数分布不可跨模型比较**：two_ 0.8668 与本地 0.3228 无直接可比性；two_ 阈值默认 0.0，业务阈值须来自 two_ 自身标注实验。
4. **远程向量非确定性**：two_ 基线以命中文档/逻辑 Chunk/Top-K 顺序为对比键；向量逐字节与耗时只报分布。
5. **modelVersion="current" 假设**：服务端无版本回显；复跑前建议复跑探针对照 manifest。
6. **本地 vector_store 含 D36 实验残留（d36exp-*，30 行）**：已知环境事实，未清理（需授权）；two_ 新表无污染。
7. **two_ RAG answer（L5）NOT RUN**：需真实 Chat 密钥与平行编排装配，任务书授权 stretch。
8. **预算余 1 次**：下轮真实调用前先规划预算。
9. 密钥纪律：DASHSCOPE_API_KEY / PG_VECTOR_PASSWORD 仅环境变量会话注入；禁写文件/日志/PATH/Git；surefire-reports 不进 Git。

## 九、官方参考资料

- 《Spring AI Reference — PGvector | Spring AI 参考文档（PGvector 向量库）》：https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- 《Spring Boot Reference — Externalized Configuration | Spring Boot 参考文档（外部化配置）》：https://docs.spring.io/spring-boot/reference/features/external-config.html
- 《pgvector — Open-source vector similarity search for Postgres | pgvector 开源向量相似检索》：https://github.com/pgvector/pgvector （HNSW 索引 vector 类型 2000 维上限见其 README）
- 《阿里云百炼 — OpenAI 兼容接口 | DashScope compatible-mode》：https://help.aliyun.com/zh/model-studio/developer-reference/compatibility-of-openai-with-dashscope
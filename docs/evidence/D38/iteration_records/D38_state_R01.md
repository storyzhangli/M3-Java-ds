# D38 迭代状态文件 R01 — 百炼 Embedding 双模型与 two_ 隔离链路

- 迭代编号：D38｜切片：R01｜记录时间：2026-11-21 19:05（Asia/Shanghai）
- 仓库根：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds`
- 任务书：`docs\temp_docs\D38\D38-项目迭代任务书Prompt-百炼Embedding双模型与two_隔离链路.md`
- git HEAD（迭代起点）：`51df3689ac09e539451eb89087915ea103e89685`（工作区起点干净；本轮未执行任何 git commit/push）
- 技术基线：JDK 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / PostgreSQL 18.7 + pgvector 0.8.7 / MySQL 8（ai_db）

## 一、总体结论

**PASS（L5 stretch 为 NOT RUN，任务书授权）**。LOCAL 哈希链路零行为改动（回归质量字段与 D37 基线一致）；百炼 `qwen3.7-text-embedding`（1024 维，探针实测冻结）经 two_ 全套新表/新端点独立运行；真实闭环入库 8 片段、幂等复跑 0 API 调用、冒烟 6/6 PASS、调用台账 17/18 未超预算、密钥扫描全部 PASS。

## 二、分级验收 L1–L5

| 级别 | 判定 | 证据与说明 |
|---|---|---|
| L1 环境探测与双轴判定 | PASS | 三环境变量（DASHSCOPE_API_KEY / BAILIAN_EMBEDDING_BASE_URL / PG_VECTOR_PASSWORD）User+Process 存在性 True（只验存在未打印值）；PG 5432 与百炼域名 443 可达；LOCAL 默认装配零变化（启动日志 pgvector 环境报告 healthy=true, vector(384)） |
| L2 探针冻结 | PASS | `probe\embedding-probe.json`（P1：HTTP 200、model 回显一致、dims=1024、L2norm=1、18 tokens、563ms）；`probe\embedding-probe-batch.json`（P2：indexes 0,1 对齐、各 1024 维、norm=1、40 tokens、623ms）；`probe\two-space-manifest.json` 冻结 |
| L3 two_ 表与幂等 DDL | PASS | PG `two_vector_store` vector(1024) + `two_vector_store_hnsw_idx`（HNSW cosine）；MySQL `two_vector_index_task` / `two_vector_chunk_index`；DDL 重跑 0 报错 0 数据变化（C05）；旧表旧脚本 git diff 为零；HNSW 上限分支见 C04 |
| L4 two_ 链路真实闭环 | PASS | 入库 8 片段（two_vector_store=8、任务 3 COMPLETED、Chunk 8 INDEXED）；幂等复跑 0 调用；冒烟 6/6 PASS；本地回归质量字段一致；ledger 17≤18。基线目录 `baseline\v1.0\TWO_BAILIAN\20261121-185541\` |
| L5 two_ RAG 端到端（stretch） | NOT RUN | 前置条件：真实 Chat 密钥与 two_ answer 链路装配。本轮 app.ai.mode=mock，预算优先冒烟与回归；任务书授权不做即标 NOT RUN，不伪称 |

## 三、条款验收 C01–C16

| 条款 | 判定 | 证据与说明 |
|---|---|---|
| C01 默认配置启动回归 | PASS | 应用 14.083s 启动成功；LOCAL 装配/端点行为不变。**方案偏差登记**：任务书预设 @Primary 解歧义，实际采用方案 B 极致版（two_ 组件全部手动装配、不注册第二个同类型 Bean），`PgVectorStoreConfiguration` 零改动、无需 @Primary，隔离更强 |
| C02 模型名探针 | PASS | P1 HTTP 200 且 model 回显 `qwen3.7-text-embedding`，名称有效，无需候选清单 |
| C03 批量/encoding 探针 | PASS | P2 两条批量 indexes=0,1 对齐；encoding_format=float 解析正确 |
| C04 D>2000 分支 | PASS（守卫已实现，分支未触发） | 实测 1024≤2000，HNSW 可建（manifest 冻结）；`TwoPgVectorConfiguration.validate` 实现 HNSW>2000 拒绝装配守卫（改 NONE/IVFFLAT 或降维提示） |
| C05 two_ DDL 幂等 | PASS | PG/MySQL DDL 各执行 2 次：第二次全部 NOTICE skipping / IF NOT EXISTS，计数不变（PG 8、MySQL 8）；旧表零触碰 |
| C06 维度三方强校验 | PASS | 配置 1024 == 模型 dimensions() 1024 == 探针冻结 1024；`TwoPgVectorConfiguration.validate` 配置≠模型即拒绝装配；`BailianEmbeddingModel.parseVectors` 响应维度≠冻结维度抛 EMBEDDING_RESPONSE_INVALID（桩测试 t4 实证） |
| C07 密钥缺失 fail-fast | PASS | BAILIAN+空 key：构造器即抛 AI_CONFIG_ERROR（桩测试 t11）+ 配置层 keyRequired 校验；LOCAL+空 key：本轮启动正常（LOCAL 不要求 key） |
| C08 入库 8 片段 | PASS（含偏差登记） | two_vector_store=8 行，metadata 含 vectorSpaceId/model/dims（VectorMetadataBuilder.buildTrace + 描述符）。**偏差**：任务书预期"ledger 1 批"，实际 3 批（seq 3/4/5，batch=3/3/2）——既有编排按文档逐篇 indexChunks，每文档独立 vectorStore.add，8 片段分属 3 文档故 3 次 HTTP；语义与预算均满足 |
| C09 幂等复跑 | PASS | 二次 corpus/index：3 任务全部 idempotent=true、processed=0；ledger 复跑前后条数不变（0 API 调用）；行数不变（8/8） |
| C10 冒烟检索 ≤6 问 | PASS | 6 问全部 two_ 空间（vs_3a789450fd0d86601065c98057ac1adb）；QA-001/QA-004/QT-001 命中预期文档与逻辑 Chunk；QU-001/QU-003/QF-001 问题集 expectedChunkIds 为空 → availability-only 判定（规则已写入脚本与文档）。分数方向回显 COSINE_SIMILARITY（越高越相似）。**口径说明**：阈值 0.0 下 QU 类原始检索仍返回候选（设计使然），决策级过滤属 RAG 链路（L5 NOT RUN） |
| C11 429/超时/401 夹具 | PASS | 桩测试：401 立即失败不重试（1 次 HTTP）；429 重试 1 次后成功（2 次 HTTP）；500 连败 2 次调用后熔断打开，第 3 次 0 HTTP；异常消息无密钥（t2/t3/t6/t8） |
| C12 维度错误/乱序 index 夹具 | PASS | 维度 3≠4 受控拒绝 EMBEDDING_RESPONSE_INVALID（t4）；乱序 index 按序对齐（t1）；index 非法/重复拒绝（parseVectors 守卫） |
| C13 跨空间隔离 | PASS | 装配日志：two_ table=public.two_vector_store，本地 report table=public.vector_store；实证：本地 QA-001 命中含 d36exp-* 残留文档（仅存于 vector_store），two_ 命中仅 3 语料文档（two_vector_store 全新无污染）；TwoVectorChunkRepository 全方法 SQL 指向 two_ 表；表名 two_ 前缀强校验 |
| C14 本地链路回归对照 | PASS | 既有 search/answer 各 1 次（QA-001）：documentHit=true、chunkHit=true、decision=ANSWER，与 D37 PGVECTOR 基线（20261120-205151）质量字段逐项一致；`06-local-regression.json` |
| C15 日志/证据密钥扫描 | PASS | 基线目录全部 JSON + ledger + 应用日志 D38-app-R01.log：sk-/Bearer 模式扫描 0 命中 |
| C16 调用预算审计 | PASS | ledger 合计 17≤18（探针 2 + 入库 3 + 检索 12）；未触发预算熔断；余 1 次 |

四态合计校验：冒烟 PASS=6 / FAIL=0 / BLOCKED=0 / NOT RUN=0，合计 6 = 用例数 6。L5 单独 NOT RUN（stretch）。

## 四、Bean 装配方案定案登记（方案 B 极致版）

**决策**：two_ 链路不注册任何与本地链路同类型的第二个 Spring Bean。

- 新增 Bean 仅 2 个：`TwoPgVectorConfiguration`（@Configuration，门禁 `app.vectorstore.two-pgvector.enabled`，主配置显式 true，test/experiment profile 显式 false）与 `TwoKnowledgeRetrievalController`（同门禁）；业务组件经唯一持有器 `TwoSpaceComponents`（record）暴露。
- `BailianEmbeddingModel` / two_PgVectorStore / two_ `DocumentVectorIndexService` / `SimilarityRetrievalService` / `QueryEmbeddingService` / `TwoVectorChunkRepository` / `PgVectorAccess`(two_) 全部在配置类内手动构造。
- two_ 配置经 `Binder.get(environment).bind("app.embedding.two" / "app.vectorstore.two-pgvector")` 绑定为普通对象，不注册 @ConfigurationProperties Bean。
- 因此 `EmbeddingModel` / `VectorStore` / `PgVectorStore` / `PgVectorStoreProperties` / `EmbeddingProperties` / `VectorChunkRepository` 六种按类型注入点全部保持单候选，**无需 @Primary，PgVectorStoreConfiguration 与 EmbeddingModelConfiguration 零改动**。
- 复用而非复制：入库幂等/版本切换/Outbox/检索编排全部复用既有类（仅替换依赖实例）；唯一复制件为 `TwoVectorChunkRepository`（父类 SQL 内联表名且映射器 private，任务书禁止参数化改造旧类）。
- PG 连接池独立 `two-pgvector-pool`（max 2 / minIdle 0 / connTimeout 5s / initFailTimeout 1s），不暴露 DataSource/JdbcTemplate Bean（避免 JdbcTemplateAutoConfiguration 退让）；DisposableBean 关池。

## 五、真实调用台账汇总（预算 18）

| 阶段 | seq | 次数 | tokens | 说明 |
|---|---|---|---|---|
| 探针 P1/P2 | 1–2 | 2 | 18+40=58 | Phase1 冻结 |
| two_ 入库 | 3–5 | 3 | 175+172+114=461 | 3 文档各 1 批（3/3/2 片段） |
| 冒烟检索 | 6–17 | 12 | 334 | 6 问 × 2 次/问（见偏差 2） |
| 幂等复跑 | — | 0 | 0 | isIdempotentHit 短路 |
| 本地回归 | — | 0 | 0 | LOCAL 哈希 + mock，无远程调用 |
| **合计** | | **17** | **853** | 余 1 次；全部 HTTP 200 |

## 六、偏差与超预期发现登记

1. **C08 批次数**：预期 1 批 → 实际 3 批（按文档编排，见 C08）。
2. **每次检索双重 Embedding（重要成本事实）**：`SimilarityRetrievalService` 先经 `QueryEmbeddingService.embed` 预向量化（校验+摘要），`PgVectorStore.similaritySearch` 内部再向量化一次（Spring AI 1.1.8 SearchRequest 只接受文本，既有类 javadoc 已预警）。台账成对相同 token 数（27/27、32/32、27/27、29/29、28/28、24/24）实证。本轮 6 问耗 12 次调用。后续优化方向（本轮不做，最小改动）：查询向量缓存、自定义 VectorStore 包装透传预计算向量、或检索摘要降级为可选。
3. **QF-001 为 availability-only**：D37 问题集中 QF-001 expectedChunkIds 为空（标注事实），冒烟按可用性断言，不臆造阈值。
4. **modelVersion="current" 假设**：服务端无版本回显，已登记于 manifest；若服务端静默升级模型，向量空间语义可能漂移——indexVersion 与 vs_id 是人工冻结锚点，复跑前应对照探针。
5. **本地 vector_store 含 D36 实验残留（d36exp-* 前缀，30 行）**：D37 基线本身含此噪声，本轮不清理（禁破坏性操作）；two_ 新表天然无污染，构成隔离红利。C14 只对照质量字段，不受影响。
6. **归一化决策**：探针实测 norm=1，客户端不再归一化；若响应范数偏离 1 超过 1e-3，客户端 L2 归一化并 WARN 一次（不静默），代码与文档均已登记。
7. **分数分布差异**：two_ QA-001 topScore=0.8668 vs D37 本地 0.3228——百炼与本地哈希分数分布完全不同，two_ 阈值默认 0.0，禁止照搬本地 0.15 经验值下结论。

## 七、修改文件清单（git 审计）

既有文件修改（5 个，+79/-1，全部登记）：
- `D38/D38_QWE_MROQRE/src/main/java/com/zl/d38/knowledge/embedding/EmbeddingProvider.java`（+BAILIAN 枚举值；全仓无 provider switch 穷举，编译安全已验证）
- `.../embedding/EmbeddingModelCatalog.java`（+BAILIAN_QWEN37_TEXT_EMBEDDING 候选元数据，价格 null 不参与计费结论）
- `.../src/main/resources/application.yml`（+app.embedding.two / app.vectorstore.two-pgvector / access-log 排除 /api/v1/two/**）
- `.../src/test/resources/application-test.yml`（two-pgvector enabled false）
- `.../src/test/resources/application-experiment.yml`（two-pgvector enabled false）

新增文件：
- main：`com/zl/d38/two/` 6 类（BailianEmbeddingModel / TwoVectorChunkRepository / TwoCorpusIndexService / TwoSpaceComponents / TwoPgVectorConfiguration / TwoKnowledgeRetrievalController）
- test：`com/zl/d38/two/BailianEmbeddingModelTest.java`（11 用例；D38 模块测试目录原无测试类，本测试即全量 surefire 套件）
- DDL：`docs/temp_docs/D38/two-pgvector-schema-idempotent.sql`、`two-schema-vector-index.sql`
- 工具：`docs/temp_docs/D38/ExecuteTwoDdl.java`（JDBC DDL/计数）、`run-two-verification.ps1`
- 证据：`docs/evidence/D38/{probe,calls,baseline,iteration_records}`

LOCAL 链路表/脚本/端点/装配类：git diff 为零（vector_store、vector_index_task、vector_chunk_index、schema-vector-index.sql、PgVectorStoreConfiguration、EmbeddingModelConfiguration、KnowledgeRetrievalController 等均未触碰）。

## 八、验证命令与真实结果

| 命令 | 结果 |
|---|---|
| `mvn -B -o -f D38\pom.xml clean test-compile` | 首次 FAILURE（测试夹具 EmbeddingOptionsBuilder 包路径错误）→ 修正为 `EmbeddingOptions.builder()`（javap 实证 1.1.8 签名）后通过 |
| `mvn -B -o -f D38\pom.xml test` | BUILD SUCCESS，Tests run: 11, Failures: 0, Errors: 0（21.6s，test profile，零计费） |
| `mvn -B -o -f D38\pom.xml clean package -DskipTests` | BUILD SUCCESS（45.7s），jar 205MB |
| psql -f two-pgvector-schema-idempotent.sql（×2） | CREATE EXTENSION/TABLE/INDEX + 重跑全 skipping，计数 8 不变 |
| java ExecuteTwoDdl.java（MySQL DDL ×2 + 计数） | 两表创建，四表共存，重跑 0 报错，计数 8 不变 |
| java -jar D38_QWE_MROQRE-1.0-SNAPSHOT.jar（mock，环境变量会话注入） | Started in 14.083s；two_ 装配日志 vs_3a789450fd0d86601065c98057ac1adb；本地 healthy=true vector(384) |
| run-two-verification.ps1 | 冒烟 6/6 PASS；幂等 PASS（0 调用）；本地回归质量字段一致；预算 17/18 PASS；密钥扫描 PASS |
| 密钥扫描（基线 JSON + ledger + 应用日志） | 0 命中 |
| git status --porcelain / diff --stat | 仅 5 既有文件 +79/-1，其余全新增；未 commit/push |

## 九、后续风险与待办

1. 检索双重 Embedding 成本优化（偏差 2）——下一迭代候选，需评估 Spring AI 1.1.8 自定义 VectorStore 包装的兼容性。
2. two_ RAG answer 链路（L5）：需真实 Chat 密钥授权与 two_ 检索接入 RagOrchestrator 的装配设计（当前 RagOrchestrator 注入本地 SimilarityRetrievalService，two_ 接入需新增平行编排，不动本地）。
3. 百炼服务端模型静默升级风险：建议定期复跑探针对照 manifest（成本 2 次调用）。
4. 预算余 1 次：下一轮真实调用前必须先规划预算或申请扩容。
5. two_ reconcile 默认 disabled（继承 app.vector-index.reconcile-enabled=false）；two_ 核对补偿未启用，残留收敛依赖复跑幂等。
6. 本地 vector_store 的 d36exp 残留清理需用户明确授权（破坏性操作，本轮未做）。
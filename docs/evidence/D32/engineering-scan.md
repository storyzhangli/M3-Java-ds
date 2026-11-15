# D32 工程扫描证据（Phase 1 真实扫描结论）

采集时间：2026-11-15
扫描对象：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D32`（模块 `D32_SSATK`）
扫描原则：只读扫描，以本地实际文件为唯一依据，不依据记忆或推测。

## 1. 工程与版本基线（来自真实 pom）

| 项 | 值 | 证据位置 |
| --- | --- | --- |
| 项目根 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D32` | 目录 |
| 模块 | `D32_SSATK`（唯一子模块） | `pom.xml` `<modules>` |
| GAV | `org.ai.m3.ds:D32:1.0-SNAPSHOT` | `pom.xml` groupId/artifactId/version |
| Parent | `org.springframework.boot:spring-boot-starter-parent:3.5.16` | `pom.xml` `<parent>` 第 15–20 行 |
| JDK | 21（source/target/release 均为 21） | `pom.xml` `<properties>` |
| Spring AI | `1.1.8`（spring-ai-bom 统一治理） | `pom.xml` `<spring-ai.version>` |
| Spring Cloud | `2025.0.0`；Alibaba `2025.0.0.0` | `pom.xml` `<properties>` 注释与属性 |
| MyBatis-Plus | `3.5.17` | `pom.xml` `<mybatis-plus.version>` |
| 主代码规模 | 207 个 `.java`（`src/main/java` 递归统计） | PowerShell `Get-ChildItem -Recurse` |
| 测试代码规模 | 5 个 `.java`（D31 快照本身 `src/test/java` 为空，本迭代新增） | 同上 |

## 2. 扫描前已有能力（D31 全量快照）

| 能力 | 真实类 | 关键事实 |
| --- | --- | --- |
| 语料加载 | `knowledge/KnowledgeCorpus` + `KnowledgeProperties` | `app.knowledge.corpus-location` 读取 `knowledge/equipment-fault-corpus.json` |
| 关键词检索（非向量） | `knowledge/KnowledgeRetrievalService` | 纯内存 bigram 打分，`maxSources` 默认 3，**不经过向量库** |
| 向量入库 | `knowledge/vectorindex/DocumentVectorIndexService` | `indexChunks` / `deleteByDocument`，稳定 UUID v5 主键、幂等、MySQL 任务表 + PG 向量表 |
| 向量检索（D31 已有） | `knowledge/vectorstore/KnowledgeVectorStoreService#search(query, topK, similarityThreshold)` | 基于 `VectorStore.similaritySearch`；`DEFAULT_TOP_K=4`、`MAX_TOP_K=50`；**无 metadata 过滤、无耗时统计、结果字段仅 6 个** |
| 检索结果模型 | `knowledge/vectorstore/VectorSearchHit` | `id/documentId/chunkIndex/title/score/contentSnippet` |
| 向量存储访问 | `knowledge/vectorstore/PgVectorAccess` | 直连 PG 做向量表存在性/行数/维度校验 |
| 统一响应 | `common/Result` + `common/ResultCode` + `GlobalExceptionHandler` | `handleEmbedding(EmbeddingException)` 已存在 |

## 3. 真实知识库语料（检索评估的唯一数据源）

文件：`src/main/resources/knowledge/equipment-fault-corpus.json`（3388 bytes）
规模：**3 篇文档 / 8 个 chunk**

| documentId | 标题 | sourceType | chunk | 章节 | 页码 |
| --- | --- | --- | --- | --- | --- |
| DOC-CNC-COOLING-001 | 数控机床冷却系统维护手册（教学夹具） | MANUAL | C01 | 第3章 冷却系统日常巡检 | 12 |
| DOC-CNC-COOLING-001 | 同上 | MANUAL | C02 | 第4章 温度传感器故障判断 | 15 |
| DOC-CNC-COOLING-001 | 同上 | MANUAL | C03 | 第5章 冷却泵与管路 | 18 |
| DOC-SERVO-TROUBLE-002 | 伺服电机常见故障排查指南（教学夹具） | GUIDE | C01 | 2.1 过载报警 | 6 |
| DOC-SERVO-TROUBLE-002 | 同上 | GUIDE | C02 | 2.3 编码器与位置偏差 | 9 |
| DOC-SERVO-TROUBLE-002 | 同上 | GUIDE | C03 | 2.4 提示注入防护教学片段 | 10 |
| DOC-WORKORDER-SPEC-003 | 设备故障工单分级与响应规范（教学夹具） | SPEC | C01 | 第2节 工单分级标准 | 3 |
| DOC-WORKORDER-SPEC-003 | 同上 | SPEC | C02 | 第4节 关单要求 | 7 |

## 4. D31 入库 metadata 真实字段（决定过滤能力边界）

来源：`DocumentVectorIndexService#toVectorDocument` 实际代码

| 字段 | Java 类型 | 是否可用于过滤 | 说明 |
| --- | --- | --- | --- |
| `documentId` | String | 是 | 文档业务主键 |
| `title` | String | 否（本迭代不开放） | 含中文与括号，白名单外字符 |
| `version` | String | 是 | 语料版本，如 `corpus-v1` |
| `chunkIndex` | Integer | 否 | 数值字段，`eq` 语义可用但业务无过滤诉求 |
| `strategy` | String | 是 | `FIXED_LENGTH` / `STRUCTURE_AWARE` |
| `pageNumbers` | List | 否 | 列表字段，`Filter` EQ 不适合 |
| `characterCount` | Integer | 否 | 同上 |
| `vectorSpaceId` | String | 否 | 内部一致性字段 |
| `modelName` | String | 否 | 模型标识 |
| `chunkContentHash` | String | 否 | 幂等校验字段 |
| `splitVersion` | String | 否 | 切分版本 |
| `distance` | Float | 否 | 框架写回的距离，只读使用 |

结论：对外可安全开放为过滤字段的只有 **`documentId` / `version` / `strategy`** 三个字符串等值字段，与实际落码一致。

## 5. 能力缺口（对照 D32 任务书 4.1–4.4）

| 任务书要求 | 现状 | 结论 |
| --- | --- | --- |
| 4.1 Query Embedding（384 维、同模型同向量空间、可观测维度/空间/耗时） | 只有入库侧 embedding，无独立 Query Embedding 组件与结果对象 | 缺口 |
| 4.2 Top-K（默认 5）COSINE 相似度检索 | 只有 `KnowledgeVectorStoreService` 内部默认 4，无 Top-K 契约、无耗时、无分数语义标注 | 缺口 |
| 4.3 真实来源信息 + metadata 过滤 | 结果仅 6 字段；无过滤能力 | 缺口 |
| 4.4 检索接口与结果结构 | 无检索 REST 接口（只有对话链内部的 RAG 检索调用） | 缺口 |
| 5 十个业务问题真实检索与逐条标注 | 无评估记录 | 缺口 |
| 6 阈值与指标口径 | 无统一 `app.retrieval` 配置 | 缺口 |

## 6. 扫描结论

1. D32 在 D31 快照上**不重复实现入库**，只补「检索侧」四层能力：Query Embedding、Top-K 检索、结果适配、接口。
2. 复用 D31 的 `DocumentVectorIndexService#indexChunks` 完成语料入库，保证向量空间、主键、幂等口径与入库侧完全一致。
3. 过滤字段严格收敛到真实存在的三个字段，其他 metadata 只回传不参与过滤。
4. 真实检索验收必须连接真实 MySQL + PostgreSQL/pgvector，禁止 Mock。

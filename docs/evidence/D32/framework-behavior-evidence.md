# D32 框架行为实证（javap 字节码证据）

目的：D32 的关键设计（能否传入预计算向量、默认 Top-K、分数语义、SQL 形状、阈值换算）全部依赖 Spring AI 1.1.8 / PgVectorStore 的真实行为，本文件只记录**字节码/常量池可验证的事实**，不依据经验推测。

字节码原件保留在 `docs\temp_docs\D32\`（未删除，交由用户决定是否清理）：

| 文件 | 大小 | 内容 |
| --- | --- | --- |
| `javap-PgVectorStore.txt` | 47489 bytes | `PgVectorStore` 全量方法签名与字段 |
| `javap-PgDistanceType-verbose.txt` | 18447 bytes | 内部枚举 `PgVectorStore$PgDistanceType` 常量池（COSINE SQL 模板） |
| `javap-DocumentRowMapper.txt` | 5804 bytes | 内部类 `DocumentRowMapper#mapRow` 字节码（分数语义） |

## 1. `SearchRequest` 没有 `queryVector`

证据：`javap` 全量方法列表只有 `query(String)` 等构建方法，无接收 `float[]`/`Embedding` 的方法。

后果：**无法把已算好的 384 维查询向量直接交给 `VectorStore.similaritySearch`**。框架会在 `getQueryEmbedding` 内部再次调用 `EmbeddingModel.embed(String)`。

工程含义：
- 本迭代的 `QueryEmbeddingService` 负责生成并暴露可观测的 Query Embedding（维度/空间/模型/耗时），用于验收与诊断；
- 真实检索路径上存在 **双次向量化**（显式一次 + 框架内部一次）。本地确定性 hash-ngram 模型零成本、无外部调用，因此不构成缺陷；换成远程 embedding API 时必须按"双次计费/双次延迟"评估，这是能力限制，已在代码注释与文档中记录。

## 2. `SearchRequest.DEFAULT_TOP_K = 4`

证据：`javap -constants` 读取 `SearchRequest` 常量：`DEFAULT_TOP_K = 4`、`SIMILARITY_THRESHOLD_ACCEPT_ALL = 0.0`。

后果：若业务不显式传 `topK`，框架默认返回 4 条，**不满足 D32 的"默认 5"**。

处理：`RetrievalProperties.defaultTopK=5` 显式兜底，任何未传 topK 的请求都落为 5。

## 3. COSINE 距离 SQL 模板与索引算子

证据：`PgDistanceType` 常量池（COSINE_DISTANCE 分支）：

```sql
SELECT *, embedding <=> ? AS distance FROM %s WHERE embedding <=> ? < ? %s ORDER BY distance LIMIT ?
```

索引创建语句使用 `vector_cosine_ops`（见 `docs\evidence\D32\db-verification.txt` 真实索引定义）。

后果与含义：
- `<=>` 是 pgvector 的余弦**距离**算子（0 表示同向，1 表示正交，2 表示反向）；
- 排序使用 `ORDER BY distance ASC`，即距离越小越相似；
- 过滤条件 `embedding <=> ? < ?` 的第三个参数不是相似度阈值本身。

## 4. 阈值换算：SQL 第三参数 = `1 - similarityThreshold`

证据：`PgVectorStore#doSimilaritySearch` 字节码出现 `dconst_1`（常量 1.0）+ `getSimilarityThreshold` + `dsub`（减法），即 `1.0 - threshold`。

后果：业务层传入 `similarityThreshold=0.0` 时，SQL 实际条件是 `distance < 1`，等价"接受所有余弦相似度 > 0 的向量，丢弃相似度 ≤ 0（正交或反向）的向量"。

这就是为什么：**阈值 0 并不等于"返回全部行"**。当语料中存在与查询近似正交的向量时，Top-K 可能返回不足 K 条（本次 `topK=50` 实测只返回 4 条，见 `retrieval-test-console.txt`）。

## 5. 分数语义：`Document.getScore() = 1 - cosine distance`

证据：`DocumentRowMapper#mapRow` 字节码：
- `metadata.put("distance", distance)` —— 原始余弦距离（`ResultSet.getFloat("distance")`）；
- `Document.builder().score(1 - distance)` —— 相似度。

后果：`Document.getScore()` 返回的是**余弦相似度**（越大越相似），而 `metadata.distance` 是余弦距离（越小越相似），两者互补：`score + distance ≈ 1.0`。

D32 在结果契约中同时返回两个字段并显式标注语义（`COSINE_SIMILARITY` / `COSINE_DISTANCE`），避免调用方把分数当距离用。集成测试用实测 `score=0.352904 / distance=0.647096`（和 ≈ 1.0）与自查询 `score=1.000000 / distance=0.000000` 双重验证。

## 6. 过滤表达式 API

证据：`FilterExpressionBuilder` 提供 `eq/ne/gt/gte/lt/lte/and/or/in/nin/isNull/isNotNull/group/not`，`Op.build()` 返回 `Filter.Expression`；`SearchRequest.Builder#filterExpression(Filter.Expression)` 接受该对象。

后果：metadata 过滤是服务端下推（进入 SQL 的 `%s` 位置），不是客户端二次筛选，因此结果数量会真实收缩（本次实测无过滤 7 条、加 `documentId` 过滤后 2 条）。

## 7. 表初始化行为

证据：`PgVectorStore.Builder` 的 `initializeSchema` / `removeExistingVectorStoreTable` 开关。

后果：D32 集成测试对独立表 `d32_it_vector_store` 显式 `initializeSchema(false)` + `removeExistingVectorStoreTable(false)`，表由测试自身 DDL 创建，避免框架删除既有表；主表 `vector_store` 全程不写入。

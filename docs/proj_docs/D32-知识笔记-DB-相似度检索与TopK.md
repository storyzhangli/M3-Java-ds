# D32 知识笔记：相似度检索与 Top-K

> 技术基线：JDK 21 / Spring Boot 3.5.16 / Spring AI 1.1.8 / PostgreSQL 18.7 / pgvector 0.8.7
> 向量维度：384（local-hashing-384，对齐 bge-small-zh 口径）
> 默认度量：余弦距离 `<=>`；索引：HNSW（`vector_cosine_ops`）
>
> 口径标记：【官方】= Spring AI / PostgreSQL / pgvector 官方文档；【实测】= D30 已在真实环境跑通的事实；【原理】= 机制解释；【结论】= 工程判断；【待执行】= 本次受环境限制未跑、附 SQL 的验证项，不编造数字。

---

## 1. 相似度与距离：先分清三个运算符

### 1.1 几何直觉

【原理】一条文本被 Embedding 模型映射成 384 维空间里的一个点/向量。两段文本语义越接近，它们的向量方向越靠近。**相似度衡量的是"方向夹角"，不是"空间距离远近"**——这正是为什么用余弦而不是直线距离。

余弦相似度：

```
cos_sim(A, B) = (A·B) / (‖A‖·‖B‖)
```

取值范围 `[-1, 1]`：方向相同为 1，垂直为 0，反向为 -1。

### 1.2 pgvector 三个运算符

| 运算符 | 含义 | 取值 | 越小表示 |
|---|---|---|---|
| `<=>` | Cosine Distance = `1 - cos_sim` | 理论 `[0, 2]` | 越相似 |
| `<->` | L2 / Euclidean Distance = `‖A-B‖` | `[0, +∞)` | 向量越近 |
| `<#>` | Negative Inner Product = `-(A·B)` | 无界 | 内积越大（pgvector 取负是为了统一"越小越相似"的排序方向） |

【实测】D30 真实记录：一条负相关样本的余弦距离是 **1.237230**——**大于 1 不是报错，而是说明两个向量方向相反（cos_sim 为负）**。很多新人看到 distance>1 第一反应是数据错了，其实它只是在说"这俩语义反着"。

【结论】**距离和相似度的排序方向相反，不能混用**：

- 距离（`<=>`）：`ORDER BY embedding <=> :q ASC`，越小越像；
- 相似度（cos_sim = `1 - distance`）：越大越像。

Spring AI 对外暴露的是"分数"，返回前做了转换——见 §6.2，别拿它直接当 pgvector 的 distance 比大小。

### 1.3 向量归一化为什么关键

【原理】如果两个向量都做过 L2 归一化（‖A‖=‖B‖=1），那么 `A·B = cos_sim`，此时内积和余弦等价。但只要向量没有归一化，**内积同时被"方向"和"长度"影响**，直接比 `<#>` 会失真。

【实测】D30 实测 `vector_norm(embedding) = 13.7613`，**不是 1**——说明当前 local-hashing 向量没有做单位归一化。这是一个关键结论：

- 必须用 `<=>`（余弦）做检索，不能图快用 `<#>`（内积）；
- HNSW 索引必须建在 `vector_cosine_ops` 上。

【实测】错配的代价 D30 已经量过：在 `vector_cosine_ops` 的 HNSW 索引表上用 L2（`<->`）排序，索引失效退化为 Seq Scan，单次 1.122 ms；而正确走余弦索引时是 0.209 ms。**运算符和 opclass 必须成对出现，错了索引静默失效。**

### 1.4 两个常见误区

【结论】

1. **相似度分数 ≠ 答案正确概率**。cos_sim=0.83 只表示"方向相近"，不表示"这条知识能答对这个故障"，更不表示"相关度 83%"。它是排序工具，不是置信度。
2. **查询向量和文档向量必须出自同一个模型**。不同模型的向量空间不兼容（D31 §7.2 已展开），跨模型比 `<=>` 没有意义。

---

## 2. Top-K 检索：先理解它在排序后做什么

### 2.1 SQL 本质

```sql
SELECT content, embedding <=> :query_vec AS distance
FROM rag_chunk
ORDER BY embedding <=> :query_vec
LIMIT :k;
```

【原理】全表算距离 → 排序 → 只取前 k 条。这就是 KNN（K-Nearest Neighbors）。Top-K 控制的是"最多拿几条候选"，不是"拿最相关的几条"——后者还要靠阈值（§3）。

### 2.2 K 怎么取

| K 太小 | K 太大 |
|---|---|
| 漏掉关键 chunk，答案不完整 | 噪声 chunk 混进来，LLM 被干扰；上下文窗口撑爆、成本上升 |

【结论】K 的三个约束：

1. **Chunk 数量**：一个文档切成 20 片，k=3 可能只覆盖半个文档；
2. **Chunk 大小**：单片 500 token，k=6 就是 3000 token，要算进 LLM 上下文预算；
3. **业务经验起点**：设备故障手册场景，典型 k=4~8，最终靠评估集（§7）定，不是拍脑袋。

【原理】Top-K 是"候选集"，后面 Rerank / 阈值过滤（D34 主题）会再砍一刀。不要一上来把 k 调到 20 凑召回。

---

## 3. 相似度阈值 Threshold：和 Top-K 是两回事

### 3.1 各自管什么

【结论】这是最容易混淆的一点：

- **Top-K**：最多取几条（数量上限）；
- **Threshold**：距离要小到什么程度才算"够相关"（质量门槛）。

执行顺序：先按距离排序取 k 条 → 再逐条和阈值比 → 不达标的扔掉。最后返回的条数 **≤ k**。

```sql
WITH ranked AS (
  SELECT c.*, c.embedding <=> :q AS distance
  FROM rag_chunk c
  ORDER BY c.embedding <=> :q
  LIMIT 50          -- 先放大候选
)
SELECT * FROM ranked
WHERE distance < :threshold_distance   -- 再过门槛
ORDER BY distance;
```

### 3.2 阈值怎么定，而不是凭感觉

【结论】

- 阈值太高 → 漏召回（明明有相关知识却判不相关，模型只能空编）；
- 阈值太低 → 噪声召回（把说明书目录页也拉进来）；
- **不同模型、不同语料、不同问题类型的阈值不能通用**——换了 Embedding 模型，距离分布整体平移，老阈值直接失效。

【待执行】正确做法：拿 20~50 条真实故障问法做样本，跑距离分布，看"相关样本距离"和"无关样本距离"在哪断开，取断开点当阈值。本笔记不替你写数字。

### 3.3 没有结果怎么办

【结论】距离全超阈值 → **宁可不召回，也别硬塞无关上下文给模型**。正确行为是回答"知识库中未找到该故障相关条目"，而不是让 LLM 凭参数脑补——这是 RAG 降低幻觉的核心闸门之一。

---

## 4. Metadata Filter：在排序之前先缩小范围

### 4.1 它解决什么

设备故障库里可能同时有 MP500、MF400、iP2700 多型号手册。用户问"MP500 换墨盒"，语义上最像的可能是 MF400 的换墨章节——**纯向量检索会跨型号误召回**。必须先用业务字段把范围圈死：

```sql
SELECT content, c.embedding <=> :q AS distance
FROM rag_chunk c
JOIN rag_document d ON d.doc_key = c.doc_key
WHERE c.doc_version = d.current_version
  AND c.model = d.current_model
  AND d.title LIKE 'MP500%'              -- 设备型号过滤
  AND c.content_sha IS NOT NULL
ORDER BY c.embedding <=> :q
LIMIT :k;
```

【原理】Metadata 在 Spring AI 里是每个 Document 的 `Map<String,Object>`；pgvector 的 PgVectorStore 把它存成 **JSONB 列**，过滤就是 `metadata->>'key' = 'value'`。它本质就是 SQL WHERE，只是由框架的 FilterExpression DSL 拼出来。

### 4.2 过滤 + ANN 的坑

【原理】HNSW 索引扫描是按距离"顺路捞"数据的。当 WHERE 条件把大部分行过滤掉后：

- ANN 捞出的近邻可能全被 WHERE 滤掉 → **最终返回不足 k 条，甚至 0 条**；
- 这不是 bug，是"索引扫描后过滤"的固有代价。

【官方】pgvector 较新版本支持 iterative index scan（索引扫描+过滤反复迭代直到凑够 k 条）来缓解这个问题；具体版本行为以 pgvector 0.8.7 文档为准。

【结论】工程应对：

1. 高基数过滤字段（device_type、tenant_id）考虑部分索引或分区；
2. 过滤后条数不足时，先放宽过滤再报警，而不是悄悄返回空。

### 4.3 安全红线

【结论】**租户/文档权限过滤必须在检索阶段强制执行，不能指望"相似度高的自然是有权看的"**。一个用户的查询向量可能语义上命中另一租户的文档——没有 WHERE tenant_id = :current 隔离，相似度再准也是数据泄露。Metadata Filter 在这里是安全机制，不是优化项。

---

## 5. 向量索引：Exact vs ANN，HNSW 在干嘛

### 5.1 为什么需要索引

【原理】无索引时，每次查询都要把表里所有向量和查询向量算一遍距离（顺序扫描）。文档到十万级、百万级，单次查询秒级起步。向量索引（尤其 ANN）用"允许少量误差"换"数量级提速"。

| | Exact（精确搜索） | ANN（近似搜索） |
|---|---|---|
| pgvector 默认行为 | 建索引前就是 Seq Scan | 建 HNSW/IVFFlat 后 |
| 结果 | 绝对最相似的 k 条 | 大概率是，允许漏几条 |
| 速度 | 慢，随数据量线性涨 | 快，近似 O(log) |
| 召回 | 100% | 受 ef_search 等参数控制 |

### 5.2 HNSW 结构

【原理】HNSW（Hierarchical Navigable Small World，分层可导航小世界图）：

- 向量被组织成多层图：上层稀疏、长距离"高速公路"，下层稠密、精细邻居；
- 查询从顶层入口点出发，贪心跳向更接近目标的邻居，逐层下探；
- 本质是"用图结构模拟人脑先粗定位再细看"。

关键参数：

| 参数 | 作用 | 权衡 |
|---|---|---|
| `m` | 每个节点最大连接数 | 越大图越密、召回越高、内存越大 |
| `ef_construction` | 建索引时搜索宽度 | 越大索引质量越好、构建越慢 |
| `ef_search` | 查询时候选搜索宽度 | 越大召回越高、查询越慢（运行时可调 `SET hnsw.ef_search = 100`） |

### 5.3 HNSW vs IVFFlat

【结论】

- **IVFFlat**：先把数据聚类成 `lists` 个桶，查询只搜最近的几个桶。需要先有数据"训练"聚类中心，小数据集效果差；
- **HNSW**：不依赖训练数据，开箱召回更高，代价是内存占用更大。pgvector 官方和本项目默认都选 HNSW。

### 5.4 Operator Class：索引和度量必须配套

```sql
-- 正确：余弦检索用余弦索引
CREATE INDEX idx_rag_chunk_hnsw ON rag_chunk
USING hnsw (embedding vector_cosine_ops);
-- L2 检索要建 vector_l2_ops；内积检索要建 vector_ip_ops
```

【实测】D30 的对比直接证明这一点：

| 查询方式 | 执行计划 | 耗时 | buffers |
|---|---|---|---|
| 余弦排序 + 余弦 HNSW | `Index Scan using idx_..._hnsw_cos` | 0.209 ms | 173 |
| 无索引余弦 | Seq Scan | 1.936 ms | 400 |
| L2 排序 + 余弦索引（错配） | 退化为 Seq Scan | 1.122 ms | 400 |

【结论】**建了索引不等于用上了索引**。每次改度量或改索引定义，都要用 `EXPLAIN ANALYZE` 确认执行计划里出现 `Index Scan using ...`，而不是 Seq Scan。

---

## 6. Spring AI 1.1.8 检索 API 关联

### 6.1 调用链

```java
// 1. 构造查询请求
SearchRequest req = SearchRequest.query("MP500 怎么换墨盒")
        .withTopK(6)
        .withSimilarityThreshold(0.75)
        .withFilterExpression("device == 'MP500' && status == 'ACTIVE'");

// 2. 交给 VectorStore（PgVectorStore bean）
List<Document> docs = vectorStore.similaritySearch(req);
```

【官方】Spring AI 的抽象：

- `VectorStore`：向量存储接口，`similaritySearch(SearchRequest)` 是统一入口；
- `SearchRequest`：承载查询文本、topK、阈值、过滤表达式；
- `FilterExpression`：声明式过滤 DSL，最终翻译成 pgvector 的 `metadata->>'...' = ...`；
- PgVectorStore 负责把上面这组参数拼成 §2.1 那条 SQL 并执行。

【待核】默认 topK、默认阈值的具体数值，以 Spring AI 1.1.8 官方 Javadoc / 参考文档为准，不在本笔记中写死其他版本的数字。

### 6.2 返回分数和 pgvector 距离不是一回事

【原理】

- pgvector 存的、SQL 里 `embedding <=> :q` 算出来的是**距离**，越小越好；
- Spring AI 返回 `Document.getScore()` 时，按当前度量做了转换：余弦度量下分数 ≈ `1 - cosine_distance`，**越大越好**。

【结论】看日志时别把这两个数直接对比。要看原始距离，就去 psql 里自己跑 SQL。

---

## 7. 检索质量评估：Recall/Precision/MRR/NDCG

【结论】光看"返回了 6 条"什么也说明不了。四个基础指标：

| 指标 | 含义 | 一句话 |
|---|---|---|
| Recall@K | Top-K 中相关条目数 ÷ 全部相关条目数 | 该找的找回来多少 |
| Precision@K | Top-K 中相关条目数 ÷ K | 找回来的里面有多少是对的 |
| MRR | 第一个相关结果排在第几位的倒数 | 相关结果排得够不够靠前 |
| NDCG | 考虑位置加权的排序质量分 | 排序整体合不合理 |

【原理】工程上最小可用的评估流程：人工标一份"问题→应命中 chunk"的金标集 → 跑检索 → 算 Recall@K。先有 Recall，再谈速度优化——快但找错了，比慢但找对更糟。

---

## 8. 常见问题清单

| 现象 | 根因 | 处理 |
|---|---|---|
| 建了 HNSW 还是 Seq Scan | 运算符和 opclass 不匹配，或没 ORDER BY distance LIMIT k | EXPLAIN ANALYZE 核对 |
| 跨型号误召回 | 缺 Metadata Filter | 加 device_type 过滤 |
| 结果不足 k 条 | ANN 扫描后被 WHERE 滤光 | 检查过滤条件/iterative scan |
| 距离全 >1 | 负相关样本，正常 | 别当错误 |
| 相似度忽高忽低 | 查询和文档不是同一 Embedding 模型 | 对齐 model 字段 |
| 调了阈值召回暴跌 | 换过模型没重校阈值 | 重新跑距离分布定阈值 |
| 响应变慢 | ef_search 太大 或 数据量涨过索引设计点 | 评估集回归 + 参数调整 |

---

## 9. 待真实环境执行的验证清单

【待执行】Windows 环境恢复后，在 `d31_life` 同一 schema 上跑以下 SQL（脚本随笔记交付），把输出存 `evidence/`：

1. 同一查询向量跑 `<=>` / `<->` / `<#>` 三种距离，对比排序差异；
2. k=3 / k=6 / k=10 分别看返回内容变化；
3. 设不同阈值，看条数曲线；
4. 加 `device='MP500'` 过滤，对比条数与召回；
5. 打开/关闭 HNSW，`EXPLAIN ANALYZE` 对比计划；
6. 测 `SET hnsw.ef_search` 在 4/16/100 下的召回与耗时。

下列数字当前不可填，跑完后回填：精确扫描耗时、HNSW 耗时、召回对比、阈值断点值。

---

## 10. 核心结论（验收要点）

给定一个设备故障问题，正确链路是：

1. 同一模型把问题转成查询向量；
2. `ORDER BY embedding <=> q LIMIT k` 做候选召回（HNSW 加速）；
3. Metadata Filter 圈定型号/版本/租户；
4. Threshold 砍掉不达标的距离；
5. 无结果时宁可拒答，不硬塞上下文。

判断索引是否有效：看 `EXPLAIN ANALYZE` 是不是 `Index Scan`；判断是否损失召回：用精确扫描当金标对比；判断过滤是否导致结果不足：看返回条数是否 < k，以及放宽过滤后能否召回。

---

## 附录：官方参考资料

- pgvector README | pgvector 向量类型、运算符与 HNSW 索引：[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)
- PostgreSQL EXPLAIN | PostgreSQL 查询计划解释：[https://www.postgresql.org/docs/18/sql-explain.html](https://www.postgresql.org/docs/18/sql-explain.html)
- Spring AI Vector Databases | Spring AI 向量存储与 SearchRequest：[https://docs.spring.io/spring-ai/reference/api/vectordbs.html](https://docs.spring.io/spring-ai/reference/api/vectordbs.html)
- Spring AI PgVectorStore | Spring AI PostgreSQL 向量存储：[https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html)
- Spring AI Embeddings | Spring AI 嵌入模型抽象：[https://docs.spring.io/spring-ai/reference/api/embeddings.html](https://docs.spring.io/spring-ai/reference/api/embeddings.html)

> （注：部分内容由豆包 AI 生成）
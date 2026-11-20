# D37 RAG 评测汇总报告

- 生成时间: 2026-11-20 20:53:03
- 数据集版本: v1.0
- 预热轮次: 0（不计入统计）
- 正式轮次: 1
- 原始记录条数: 20
- 对外模式: offline
- 检索后端: BLOCKED
- 降级原因: 强制 PGVECTOR 但 PG 端口或应用 HTTP 不可达（PG=true，HTTP=false）

## 四态分布

- BLOCKED: 20

## 场景指标

| 场景 | 题数 | HitRate@K(文档) | MacroRecall | microPrecision | 噪声占比 | 拒答正确率 | 误拒率 | 误答率 |
|---|---|---|---|---|---|---|---|---|
|ANSWERABLE|5|0.0000|N/A|N/A|N/A|N/A|0.0000|N/A|
|UNANSWERABLE|5|0.0000|N/A|N/A|N/A|0.0000|N/A|0.0000|
|TERMINOLOGY|5|0.0000|N/A|N/A|N/A|N/A|0.0000|N/A|
|BOUNDARY|5|0.0000|N/A|N/A|N/A|0.0000|N/A|0.0000|

## 延迟分布（毫秒，仅分布不进基线对比键）

| 阶段 | 样本数 | 均值 | P50 | P95 | min | max |
|---|---|---|---|---|---|---|
|queryEmbeddingMs|0|N/A|N/A|N/A|N/A|N/A|
|retrievalElapsedMillis|0|N/A|N/A|N/A|N/A|N/A|
|totalElapsedMillis|0|N/A|N/A|N/A|N/A|N/A|
|inMemorySearchMs|0|N/A|N/A|N/A|N/A|N/A|

## 失败标签分布（9 类一阶 + 细分原因码）


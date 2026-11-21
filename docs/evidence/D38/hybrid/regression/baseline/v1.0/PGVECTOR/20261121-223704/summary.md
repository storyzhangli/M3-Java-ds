# D37 RAG 评测汇总报告

- 生成时间: 2026-11-21 22:37:04
- 数据集版本: v1.0
- 预热轮次: 3（不计入统计）
- 正式轮次: 3
- 原始记录条数: 60
- 对外模式: offline
- 检索后端: PGVECTOR

## 四态分布

- PASS: 14
- FAIL: 6

## 场景指标

| 场景 | 题数 | HitRate@K(文档) | MacroRecall | microPrecision | 噪声占比 | 拒答正确率 | 误拒率 | 误答率 |
|---|---|---|---|---|---|---|---|---|
|ANSWERABLE|5|0.4000|0.8000|0.3333|0.6667|N/A|0.2000|N/A|
|UNANSWERABLE|5|0.0000|N/A|N/A|N/A|1.0000|N/A|0.0000|
|TERMINOLOGY|5|1.0000|1.0000|0.3571|0.6429|N/A|0.8000|N/A|
|BOUNDARY|5|0.0000|N/A|N/A|N/A|1.0000|N/A|0.0000|

## 延迟分布（毫秒，仅分布不进基线对比键）

| 阶段 | 样本数 | 均值 | P50 | P95 | min | max |
|---|---|---|---|---|---|---|
|queryEmbeddingMs|20|0.0000|0.0000|0.0000|0.0000|0.0000|
|retrievalElapsedMillis|20|3.0000|3.0000|5.0000|2.0000|5.0000|
|totalElapsedMillis|20|30.1500|22.0000|41.8500|17.0000|153.0000|
|inMemorySearchMs|0|N/A|N/A|N/A|N/A|N/A|

## 失败标签分布（9 类一阶 + 细分原因码）

- HIGH_NOISE_PRECISION: 18
- METADATA_PAGE_LOCATION_MISSING: 1
- HIGH_NOISE_TOP1: 3
- CLARIFY_UNEXPECTED: 7
- CLARIFY_EXPECTED_MISSING: 1
- MISS_RECALL_FILTERED: 1
- REFUSAL_WRONG_CODE: 2
- REFUSAL_FALSE_NEGATIVE: 5

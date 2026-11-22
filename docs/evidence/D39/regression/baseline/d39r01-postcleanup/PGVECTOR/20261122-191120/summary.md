# D37 RAG 评测汇总报告

- 生成时间: 2026-11-22 19:11:20
- 数据集版本: d39r01-postcleanup
- 预热轮次: 2（不计入统计）
- 正式轮次: 2
- 原始记录条数: 40
- 对外模式: offline
- 检索后端: PGVECTOR

## 四态分布

- FAIL: 6
- PASS: 14

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
|retrievalElapsedMillis|20|3.6000|4.0000|4.0500|3.0000|5.0000|
|totalElapsedMillis|20|33.0500|26.0000|44.8500|19.0000|156.0000|
|inMemorySearchMs|0|N/A|N/A|N/A|N/A|N/A|

## 失败标签分布（9 类一阶 + 细分原因码）

- CLARIFY_EXPECTED_MISSING: 1
- CLARIFY_UNEXPECTED: 7
- HIGH_NOISE_TOP1: 3
- METADATA_PAGE_LOCATION_MISSING: 1
- HIGH_NOISE_PRECISION: 18
- REFUSAL_FALSE_NEGATIVE: 5
- REFUSAL_WRONG_CODE: 2
- MISS_RECALL_FILTERED: 1

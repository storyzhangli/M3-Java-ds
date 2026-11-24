# D37 RAG 评测汇总报告

- 生成时间: 2026-11-24 17:02:13
- 数据集版本: v1.0
- 预热轮次: 3（不计入统计）
- 正式轮次: 3
- 原始记录条数: 60
- 对外模式: offline
- 检索后端: IN_MEMORY_LOCAL

## 四态分布

- NOT RUN: 5
- PASS: 15

## 场景指标

| 场景 | 题数 | HitRate@K(文档) | MacroRecall | microPrecision | 噪声占比 | 拒答正确率 | 误拒率 | 误答率 |
|---|---|---|---|---|---|---|---|---|
|ANSWERABLE|5|1.0000|1.0000|0.2400|0.7600|N/A|0.0000|N/A|
|UNANSWERABLE|5|0.0000|N/A|N/A|N/A|0.0000|N/A|0.0000|
|TERMINOLOGY|5|1.0000|1.0000|0.2000|0.8000|N/A|0.0000|N/A|
|BOUNDARY|5|0.0000|N/A|N/A|N/A|0.0000|N/A|0.0000|

## 延迟分布（毫秒，仅分布不进基线对比键）

| 阶段 | 样本数 | 均值 | P50 | P95 | min | max |
|---|---|---|---|---|---|---|
|queryEmbeddingMs|0|N/A|N/A|N/A|N/A|N/A|
|retrievalElapsedMillis|0|N/A|N/A|N/A|N/A|N/A|
|totalElapsedMillis|0|N/A|N/A|N/A|N/A|N/A|
|inMemorySearchMs|20|0.0000|0.0000|0.0000|0.0000|0.0000|

## 失败标签分布（9 类一阶 + 细分原因码）

- HIGH_NOISE_TOP1: 1
- HIGH_NOISE_PRECISION: 20

# D38 R02 混合检索评测汇总（策略 HYBRID_RRF）

- 生成时间: 2026-11-21 22:49:44
- 数据集版本: v1.0
- 预热轮次: 3（不计入统计）
- 正式轮次: 3
- 原始记录条数: 60
- 请求错误数: 0
- 请求超时数: 0
- baseUrl: http://127.0.0.1:8081
- port: 8081
- datasetSha256: 896bceaa1ede76dc40577a8b97473a532ba1984414ab0e52d61c0fffa5234bec
- corpusSha256: aad6ac4df5585aad5078d1c94df4e5093af15a9730e230149cfde0940335ab0b
- hybridAvailable: true
- aiMode: mock

## 四态分布

- PASS: 45
- FAIL: 15

## 场景指标

| 场景 | 题数 | HitRate@K(文档) | MacroRecall | microPrecision | 噪声占比 | 拒答正确率 | 误拒率 | 误答率 |
|---|---|---|---|---|---|---|---|---|
|ANSWERABLE|15|0.8000|1.0000|0.3750|0.6250|N/A|0.2000|N/A|
|UNANSWERABLE|15|0.0000|N/A|N/A|N/A|1.0000|N/A|0.0000|
|TERMINOLOGY|15|1.0000|1.0000|0.3571|0.6429|N/A|0.8000|N/A|
|BOUNDARY|15|0.0000|N/A|N/A|N/A|1.0000|N/A|0.0000|

## 延迟分布（毫秒，仅分布不进基线对比键）

| 阶段 | 样本数 | 均值 | P50 | P95 | min | max |
|---|---|---|---|---|---|---|
|queryEmbeddingMs|60|0.0000|0.0000|0.0000|0.0000|0.0000|
|retrievalElapsedMillis|60|4.2833|4.0000|5.0500|3.0000|14.0000|
|totalElapsedMillis|60|24.3833|23.5000|33.1000|19.0000|41.0000|
|keywordSearchMs|0|N/A|N/A|N/A|N/A|N/A|
|fusionMs|0|N/A|N/A|N/A|N/A|N/A|

## 失败标签分布

- REFUSAL_WRONG_CODE: 6
- HIGH_NOISE_PRECISION: 54
- HIGH_NOISE_TOP1: 9
- REFUSAL_FALSE_NEGATIVE: 15
- CLARIFY_UNEXPECTED: 21
- CLARIFY_EXPECTED_MISSING: 3

# D38 R02 混合检索评测汇总（策略 PURE_VECTOR）

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

- PASS: 42
- FAIL: 18

## 场景指标

| 场景 | 题数 | HitRate@K(文档) | MacroRecall | microPrecision | 噪声占比 | 拒答正确率 | 误拒率 | 误答率 |
|---|---|---|---|---|---|---|---|---|
|ANSWERABLE|15|0.4000|0.8000|0.3333|0.6667|N/A|0.2000|N/A|
|UNANSWERABLE|15|0.0000|N/A|N/A|N/A|1.0000|N/A|0.0000|
|TERMINOLOGY|15|1.0000|1.0000|0.3571|0.6429|N/A|0.8000|N/A|
|BOUNDARY|15|0.0000|N/A|N/A|N/A|1.0000|N/A|0.0000|

## 延迟分布（毫秒，仅分布不进基线对比键）

| 阶段 | 样本数 | 均值 | P50 | P95 | min | max |
|---|---|---|---|---|---|---|
|queryEmbeddingMs|60|0.0000|0.0000|0.0000|0.0000|0.0000|
|retrievalElapsedMillis|60|2.8667|3.0000|4.0000|2.0000|8.0000|
|totalElapsedMillis|60|21.0667|20.5000|27.0000|17.0000|28.0000|
|keywordSearchMs|0|N/A|N/A|N/A|N/A|N/A|
|fusionMs|0|N/A|N/A|N/A|N/A|N/A|

## 失败标签分布

- REFUSAL_WRONG_CODE: 6
- REFUSAL_FALSE_NEGATIVE: 15
- HIGH_NOISE_PRECISION: 54
- METADATA_PAGE_LOCATION_MISSING: 3
- HIGH_NOISE_TOP1: 9
- CLARIFY_UNEXPECTED: 21
- CLARIFY_EXPECTED_MISSING: 3
- MISS_RECALL_FILTERED: 3

# D29 向量空间与索引兼容性证据

## 向量空间标识

| Provider | Model | Version | Dimensions | VectorSpaceId |
|---|---|---|---:|---|
| LOCAL | `local-hash-ngram-v1` | `2026.11` | 384 | `vs_3b71fef23531baa9fc398678092333d4` |
| OPENAI | `text-embedding-3-small` | `current` | 1536 | `vs_60b3f4a90aaf65a8d7a735a130646327` |
| OPENAI | `text-embedding-3-large` | `current` | 3072 | `vs_79e34895affde22ed3e5c5f765da1200` |

## 兼容判断

`IndexCompatibilityValidator` 要求以下字段全部一致：

- provider
- modelName
- modelVersion
- dimensions
- vectorSpaceId
- indexVersion
- distanceMetric

测试结果：

| 场景 | 结果 |
|---|---|
| 完全相同的模型和索引元数据 | 兼容 |
| 不同 provider/维度 | 拒绝 |
| 同一 local provider 但 384/512 维不同 | 拒绝 |
| 同为 384 维但模型名不同 | 拒绝 |
| indexVersion 不同 | 拒绝 |
| distanceMetric 不同 | 拒绝 |

因此两个模型即使输出相同维度，也不会被默认视为兼容。

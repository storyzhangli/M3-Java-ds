# D29 真实本地 Embedding 调用摘要

## 输入

- 来源：`src/main/resources/knowledge/equipment-fault-corpus.json`
- 使用 D29 已有真实 Chunk 文本。
- 样本：中文短文、中文技术文、中英文混合、约 535 码点长文本。
- 不调用云端 API。

## 模型

| 项目 | 值 |
|---|---|
| Provider | LOCAL |
| Model | `local-hash-ngram-v1` |
| Version | `2026.11` |
| Dimensions | 384 |
| VectorSpaceId | `vs_3b71fef23531baa9fc398678092333d4` |
| IndexVersion | `d29-local-384-v1` |
| Distance | COSINE |
| Key required | false |
| API cost | $0 |

## 实际结果

- 4/4 Chunk 成功生成向量。
- 每向量 384 个有限 float。
- L2 norm 约等于 1。
- 相同文本重复调用向量完全一致。
- documentId、title、version、chunkIndex、pageNumbers、sources 和 inputHash 保留。
- Provider 没有返回 Token usage；项目记录 `NOT_APPLICABLE`，没有伪造 Token 数。

机器证据：`docs/evidence/D29/D29-local-embedding-evidence-20261112.json`。

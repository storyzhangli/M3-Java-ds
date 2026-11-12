# D29 Embedding 模型选型证据

## 官方资料查询

查询日期：2026-11-12。来源限定为 OpenAI 官方 Developers 文档：

- Embeddings 指南：https://developers.openai.com/api/docs/guides/embeddings
- `text-embedding-3-small` 模型页：https://developers.openai.com/api/docs/models/text-embedding-3-small
- 官方价格页：https://developers.openai.com/api/docs/pricing
- Embeddings Create API：https://developers.openai.com/api/reference/resources/embeddings/methods/create

## 候选对比

| 维度 | 本地 `local-hash-ngram-v1` | OpenAI `text-embedding-3-small` | OpenAI `text-embedding-3-large` |
|---|---|---|---|
| Provider | LOCAL | OPENAI | OPENAI |
| 实际可调用 | 是，本轮真实运行 | 当前未注入/调用真实 Key | 当前未注入/调用真实 Key |
| 默认维度 | 384 | 1536 | 3072 |
| 最大输入 | 12,000 Unicode code points | 8,192 Token | 8,192 Token |
| 中文支持 | 字符 n-gram 可用 | 官方说明支持多语言 | 官方说明支持多语言 |
| 调用费用 | API 费用 0；本地计算成本未计价 | $0.02/1M 输入 Token | $0.13/1M 输入 Token |
| 密钥 | 不需要 | 需要 | 需要 |
| Java 接入 | 当前 Spring AI `EmbeddingModel` Bean | 需后续远程适配器 | 需后续远程适配器 |
| 语义能力 | 词法相似，非预训练神经模型 | 官方通用 Embedding | 更高维度云端候选 |
| 索引隔离 | vectorSpaceId 由 provider/model/version/dimension 生成 | 独立空间 | 独立空间 |

## 官方约束

官方 Embeddings API 文档说明：

- 输入不能为空字符串。
- 最大输入 8192 Token。
- 单请求最多 2048 个输入。
- 单请求所有输入总计最多 300,000 Token。
- 第三代为文本模型，OpenAI 官方向量归一化；推荐余弦相似度。

## 本轮选择

选择 `local-hash-ngram-v1` 作为主模型：

- 当前环境可真实调用且无需密钥。
- 不会产生云端 Token 费用。
- 确定性适合自动化测试和索引兼容性验证。
- 能验证完整 Chunk → Embedding → 校验 → 索引空间隔离链路。
- 局限是词法特征而非深度语义模型，不能宣称接近 OpenAI 云端语义质量。

云端模型仅作为配置切换目标和后续 VectorStore 阶段的已验证官方候选，本轮没有伪造云端成功调用。

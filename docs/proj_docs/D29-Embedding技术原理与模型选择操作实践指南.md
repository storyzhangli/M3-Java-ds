# D29 Embedding 技术原理与模型选择操作实践指南

## 1. Embedding 的基本概念

Embedding 是把文本映射为稠密数值向量的过程：

```text
text -> encoder -> [v1, v2, ..., vN]
```

向量用于表达文本在某个模型定义空间中的特征。可以用向量距离或相似度做：

- 语义搜索
- 聚类
- 相似问题推荐
- 异常检测
- 分类

Embedding 不是答案文本，也不是生成模型。它只输出向量及必要元数据。

## 2. 文本转换为向量的基本原理

预训练神经 Embedding 模型通常包括：

1. Tokenization：把文本切成模型 Token。
2. Encoder：Transformer 或类似网络生成上下文表示。
3. Pooling：将 Token 表示合成为固定长度向量。
4. Normalization：按模型设计进行 L2 归一化。
5. Projection：部分模型支持将高维输出降到较低维度。

本项目本地实现采用另一种可解释方案：

1. Unicode NFKC 规范化。
2. 提取词元与字符 n-gram。
3. 对每个特征做稳定哈希。
4. 有符号映射到 384 个桶。
5. L2 归一化。

因此本地模型不需要外部推理服务，但不具备预训练模型的深层语义泛化。

## 3. Embedding 与普通文本生成模型的区别

| 维度 | EmbeddingModel | ChatModel |
|---|---|---|
| 输入 | 文本 |
| 输出 | float[] 向量 |
| 目标 | 表示文本相似性 | 生成后续文本 |
| 是否可读 | 向量通常不可直接阅读 | 输出自然语言 |
| 典型用途 | 检索、聚类 | 对话、总结、生成 |
| Token 成本 | 通常只计输入 | 通常输入 + 输出 |

项目已有 DeepSeek ChatModel，不能因此假定存在同供应商 EmbeddingModel。D29 的本地模型和 D01–D28 的聊天模型是独立组件。

## 4. 向量维度的含义

维度 N 表示每个向量有 N 个实数：

```text
[0.013, -0.224, ..., 0.071]
```

维度不是质量评分：

- 高维不等于更准确。
- 低维不等于不能使用。
- 维度由模型和配置决定。
- 索引必须固定维度或支持明确的降维规则。

本轮本地模型固定为 384 维。OpenAI 官方默认：

- `text-embedding-3-small`：1536 维。
- `text-embedding-3-large`：3072 维。

## 5. 向量语义空间

语义空间是模型训练目标、数据、编码器、维度和归一化共同定义的空间。

两个向量只有在同一空间内比较才有业务意义。例如：

```text
model A: [0.2, 0.1, ...]
model B: [0.2, 0.1, ...]
```

数组看起来相似，也不代表语义相同。模型 A 和 B 可能把完全不同的概念放在相近坐标。

## 6. 相似度计算的基本原理

常见相似度与距离：

### Cosine Similarity

```text
cos(a,b) = dot(a,b) / (||a|| * ||b||)
```

范围通常为 -1 到 1，越接近 1 越相似。

### Dot Product

```text
dot(a,b) = Σ ai * bi
```

当向量都 L2 归一化时：

```text
dot(a,b) == cos(a,b)
```

因此可用点积加速余弦搜索，但必须确保归一化前提。

### Euclidean Distance

```text
distance(a,b) = sqrt(Σ (ai-bi)^2)
```

距离越小越相似。对于已归一化向量，其排序与余弦相似度通常等价。

## 7. Cosine、Dot Product 与 Euclidean Distance

| 指标 | 对向量长度敏感 | 同归一化下关系 | 常见用途 |
|---|---|---|---|
| Cosine | 否 | 与归一化点积等价 | 文本语义检索 |
| Dot Product | 是 | 归一化后等价 Cosine | 高速内积索引 |
| Euclidean | 是 | 归一化后排序常等价 | 距离空间查询 |

OpenAI 官方说明其 Embedding 已归一化，推荐 cosine similarity。D29 本地模型也做 L2 归一化，并配置 `COSINE`。

## 8. 不同 Embedding 模型的差异

模型差异来自：

- 训练目标
- 训练数据语言分布
- Tokenizer
- Encoder 架构
- 输出维度
- 最大输入
- 归一化方式
- 是否支持 `dimensions` 降维
- 成本和可用性

不能仅根据 MTEB 或其他单一排行榜选模型。必须验证：

- 当前环境可用性
- 中文/混合文本质量
- 延迟
- 费用
- 最大输入
- 数据合规
- 索引迁移成本

## 9. 中文 Embedding 模型选择

中文场景要重点检查：

- 中文分词和字符覆盖
- 中英混合
- 技术术语、型号、故障码、数字和单位
- 长段落
- 同义词和跨表述语义
- 数据驻留与密钥要求

本轮选择本地 n-gram 模型，是因为它可在当前环境真实运行且无 Key。其优点是确定性和可测试性，局限是主要表达词法重叠。

如果正式 RAG 对语义召回要求较高，应评估：

- OpenAI `text-embedding-3-small/large`
- 经过许可和部署验证的中文本地神经模型
- 中文向量模型的服务可用性、价格和输出维度

不得用未核验的第三方宣传数据替代实测。

## 10. 最大输入 Token 限制

官方 OpenAI Embedding API 当前说明：

- 每个输入最多 8192 Token。
- 单请求最多 2048 个输入。
- 单请求所有输入总计最多 300,000 Token。
- 空字符串不允许。

本地模型的限制是 12,000 Unicode code points，因为当前没有配置可靠 Tokenizer。

## 11. 字符数与 Token 数的区别

字符数：

```text
String.codePointCount(...)
```

Token 数：

```text
Tokenizer.encode(text).length
```

两者没有稳定等价关系。中文、英文、代码、emoji 的 Token 密度不同。

项目做法：

- 本地模型配置 `UNICODE_CODE_POINTS`。
- 不把本地长度称为 Token。
- 未来接入 OpenAI 时必须用兼容 Tokenizer 或服务返回 usage。

## 12. Embedding API 调用流程

远程 API 一般流程：

1. 校验文本非空和 Token 上限。
2. 从安全环境注入 API Key。
3. 发送模型名、输入和可选维度。
4. 接收向量和 usage。
5. 校验 `data` 数量与顺序。
6. 校验每条向量维度及 finite。
7. 保存模型、向量空间、索引版本和 usage。

本轮真实调用的是本地模型：

```java
EmbeddingModel.embed(new Document(chunk.content()))
```

不经过网络，不需要 Key。

## 13. Spring AI EmbeddingModel 的作用

Spring AI 1.1.8 实际接口包括：

```java
EmbeddingResponse call(EmbeddingRequest request);
float[] embed(Document document);
int dimensions();
```

项目本地模型实现该接口，使业务调用不绑定供应商 DTO 或 HTTP 协议。

`ChunkEmbeddingService` 只依赖：

- `EmbeddingModel`
- `EmbeddingModelDescriptor`
- `EmbeddingVectorValidator`
- `RetryTemplate`

未来可替换云端或本地神经模型实现，不需要重写 Chunk 编排。

## 14. Chunk 到 Embedding 的工程流程

```text
ChunkingResult
  -> List<DocumentChunk>
  -> validate nonblank + length
  -> EmbeddingModel
  -> bounded RetryTemplate
  -> vector validation
  -> ChunkEmbedding with source metadata
```

每个 `ChunkEmbedding` 保留：

- documentId
- title
- version
- chunkIndex
- pageNumbers
- paragraph sources
- inputHash
- inputCharacters
- dimensions
- vector
- attempts

## 15. API Key 安全管理

原则：

- 真实 Key 只从环境变量或安全配置注入。
- 不写入 Git、配置模板、日志、Prompt、测试报告。
- `EmbeddingProperties` 的 Key 字段不进入 `toString()`。
- `EmbeddingModelDescriptor` 不携带 Key。
- 测试使用无效占位值。
- 云端调用前校验 Key 存在且非占位。
- 日志只记录 provider/model/dimension，不记录认证头。

当前本地模型 `keyRequired=false`，没有读取真实 Embedding Key。

## 16. Embedding 调用费用计算

公式：

```text
cost = input_tokens / 1_000_000 * price_per_million_input_tokens
```

OpenAI 官方页面当前：

- small：$0.02 / 1M input Token。
- large：$0.13 / 1M input Token。

费用只适用于真实 API usage。没有 Token usage 时不能编造。

本轮本地模型：

- API 费用 = $0。
- input tokens = 未返回。
- usage status = `NOT_APPLICABLE`。
- 本地 CPU/内存成本没有金额化。

## 17. 请求超时、限流和失败重试

可重试：

- 连接失败
- 安全读取超时
- 429 限流
- 502/503/504
- 临时网络 I/O

不可重试：

- 400 参数错误
- 401 非法 Key
- 402/额度不足
- 403 权限
- 404 模型不存在
- 空文本和非法向量

重试必须有限次数、有退避上限，并记录尝试次数。项目复用现有唯一 `RetryTemplate`，避免在 Embedding 客户端再叠加一层重试。

## 18. 向量维度与索引兼容性

维度是必要条件，但不充分：

```text
same dimensions != compatible vector space
```

索引必须保存：

- provider
- modelName
- modelVersion
- dimensions
- vectorSpaceId
- indexVersion
- distanceMetric

写入前逐项校验。

## 19. 模型切换与重新 Embedding

切换模型时：

1. 新模型创建新 model descriptor。
2. 计算新 vectorSpaceId。
3. 创建新的 indexVersion。
4. 对全量 Chunk 重新 Embedding。
5. 新旧索引并行验证。
6. 切换检索指针。
7. 旧索引保留回滚窗口后废弃。

禁止：

- 向旧索引追加新模型向量。
- 只因为维度相同就混写。
- 用旧索引直接比较新模型 query 向量。

## 20. 为什么相同维度不代表向量兼容

假设两个模型都输出 384 维：

```text
A("冷却不足") = [0.1, -0.2, ...]
B("冷却不足") = [0.1, -0.2, ...]
```

数值接近也可能只是坐标巧合。若 B 的训练目标不同，维度含义可能完全不同。

项目 vectorSpaceId 包含 provider、模型名、版本和维度，正是为了防止这种误混合。

## 21. 向量归一化与数值校验

归一化：

```java
norm = sqrt(sum(v[i] * v[i]))
v[i] = v[i] / norm
```

必须检查：

- 向量非 null
- 长度等于配置维度
- 每个值 `Float.isFinite`
- norm > 0
- norm 未溢出

如果模型未承诺归一化，不应自行假定 dot product 等价 cosine。可以在入库前按统一规则归一化，但这本身应成为索引契约的一部分。

## 22. D29 关键代码实践

### 配置

```yaml
app:
  embedding:
    provider: LOCAL
    model-name: local-hash-ngram-v1
    model-version: 2026.11
    dimensions: 384
    max-input-length: 12000
    length-unit: UNICODE_CODE_POINTS
    distance-metric: COSINE
    index-version: d29-local-384-v1
    api-key: ${EMBEDDING_API_KEY:}
```

### 调用

```java
float[] vector = embeddingModel.embed(
        new Document(chunk.content()));
vectorValidator.validate(vector, descriptor.dimensions());
```

### 索引兼容

```java
IndexCompatibility compatibility =
        validator.check(existingIndex, candidateModel);
if (!compatibility.compatible()) {
    throw new EmbeddingException(
            ResultCode.VECTOR_INDEX_INCOMPATIBLE,
            compatibility.reason());
}
```

## 23. 常见故障及排查

### 维度不一致

- 模型配置维度是否与真正输出一致。
- 是否切换模型后仍用旧索引维度。
- 是否把默认维度和通过 `dimensions` 降维后的维度混淆。

### 向量全为零

- 输入是否只有不可识别字符。
- 特征提取是否为空。
- 是否发生严重哈希碰撞。

### NaN / Infinity

- 归一化前是否出现除零。
- 模型输出是否损坏。
- 浮点累积是否异常。

### 空输入

- 上游 Chunk 是否产生空片段。
- 清洗后是否只剩空白。

### 超长输入

- 字符数和 Token 数混淆。
- Chunk 配置大于模型上限。
- 未使用可靠 Tokenizer。

### 重试次数异常

- Spring AI、HTTP 客户端和业务层是否重复叠加重试。
- 永久错误是否被错误分类为临时错误。

### 模型切换后检索乱序

- 是否复用旧 vectorSpaceId。
- 是否向旧索引混写。
- 是否重新 Embedding 全部 Chunk。

### API Key 暴露

- 检查配置、日志、异常、请求导出、指标标签和测试报告。
- 立即轮换已暴露 Key，不尝试只删日志。

## 24. 与后续 VectorStore、RAG 的衔接

推荐持久化结构：

```text
vector:
  tenantId
  documentId
  version
  chunkIndex
  vectorSpaceId
  indexVersion
  dimensions
  distanceMetric
  embedding[]
  pageNumbers[]
  sourceRanges[]
  inputHash
```

后续流程：

```text
Embedding preview
  -> approved vector generation
  -> VectorStore upsert
  -> permission-aware retrieval
  -> RAG answer with page/chunk citation
```

VectorStore 写入前必须重新运行 `IndexCompatibilityValidator`。模型或维度改变时创建新索引，不得原地混写。

## 25. 参考资料

- OpenAI Embeddings Guide：https://developers.openai.com/api/docs/guides/embeddings
- OpenAI `text-embedding-3-small`：https://developers.openai.com/api/docs/models/text-embedding-3-small
- OpenAI Pricing：https://developers.openai.com/api/docs/pricing
- OpenAI Embeddings Create API：https://developers.openai.com/api/reference/resources/embeddings/methods/create
- Spring AI 1.1 Reference：https://docs.spring.io/spring-ai/reference/1.1/

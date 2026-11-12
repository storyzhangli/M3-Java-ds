# D29 Embedding 模型接入与向量化功能实现全量说明

## 1. 文档信息

| 项目 | 内容 |
|---|---|
| 迭代名称 | Day 29：Embedding 原理与模型选择 |
| 项目根目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D29` |
| 模块 | `D29_EBD` |
| 完成日期 | 2026-11-12 |
| 目标 | 建立可调用、可校验、可计量、可切换、索引空间可追溯的 Embedding 能力 |

## 2. 目标与背景

D29 已继承 D28 的 PDF 页面解析、文本清洗和 Chunk 切分。本轮把 `DocumentChunk.content` 送入 EmbeddingModel，并解决以下问题：

- EmbeddingModel 与聊天 ChatModel 解耦。
- 模型名称、版本、维度、最大输入和 API Key 外部化。
- 向量维度、有限数值和非零范数校验。
- 空文本、超长文本和异常响应明确失败。
- 只对临时失败进行有限重试。
- 保存模型和向量空间元数据。
- 不同维度或不同模型的向量禁止混用。
- 当前环境真实调用无需密钥、无需付费 API 的本地模型。

本轮没有实现 VectorStore、向量数据库或 RAG 检索。

## 3. D28 到 D29 的功能衔接

输入链路：

```text
PDF/Word
  -> page extraction
  -> TextCleaner
  -> DocumentChunker
  -> DocumentChunk
  -> ChunkEmbeddingService
  -> EmbeddingModel
  -> Vector validation
  -> traceable embedding preview
```

`ChunkEmbedding` 保留：

- documentId
- title
- version
- chunkIndex
- pageNumbers
- sources（pageNumber + paragraphIndex）
- inputHash
- inputCharacters
- dimensions
- vector
- attempts

因此向量可以回溯到原始文档页面和 Chunk，不会只剩一个无语义浮点数组。

## 4. 当前项目技术环境

| 组件 | 版本 |
|---|---|
| JDK | 21.0.7 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| Spring MVC | Tomcat/Servlet |
| MyBatis-Plus | 3.5.17 |
| Apache PDFBox | 3.0.7，继承 D27 |
| Apache Tika | 3.3.1，继承 D27 |

现有 DeepSeek Starter 提供 ChatModel，但不表示 DeepSeek 一定有可用 Embedding 接口。项目此前没有 EmbeddingModel Bean、Embedding starter 或向量索引实现。

## 5. 模型调研与候选对比

官方资料查询日期为 2026-11-12。

| 模型 | Provider | 默认维度 | 最大输入 | 费用 | Key |
|---|---|---:|---:|---:|---|
| `local-hash-ngram-v1` | 本地 Java | 384 | 12,000 码点 | API 费用 $0 | 不需要 |
| `text-embedding-3-small` | OpenAI | 1536 | 8192 Token | $0.02 / 1M 输入 Token | 需要 |
| `text-embedding-3-large` | OpenAI | 3072 | 8192 Token | $0.13 / 1M 输入 Token | 需要 |

OpenAI 官方 Embeddings 指南还明确：

- Embedding API 输入不能为空字符串。
- 单请求最多 2048 个输入。
- 单请求所有输入总计最多 300,000 Token。
- 第三代为文本 Embedding 模型。
- 官方向量归一化，推荐 cosine similarity。

官方来源：

- https://developers.openai.com/api/docs/guides/embeddings
- https://developers.openai.com/api/docs/models/text-embedding-3-small
- https://developers.openai.com/api/docs/pricing
- https://developers.openai.com/api/reference/resources/embeddings/methods/create

## 6. 最终主模型

本轮主模型：

```text
provider: LOCAL
modelName: local-hash-ngram-v1
modelVersion: 2026.11
dimensions: 384
maxInputLength: 12000
lengthUnit: UNICODE_CODE_POINTS
distanceMetric: COSINE
indexVersion: d29-local-384-v1
vectorSpaceId: vs_3b71fef23531baa9fc398678092333d4
```

选择原因：

1. 当前环境可真实运行，不需要远程 API Key。
2. 不产生云端 Token 费用。
3. 相同输入输出稳定，便于测试维度和索引隔离。
4. 可以完整验证 D29 Chunk 到向量的工程链路。
5. 实现 Spring AI `EmbeddingModel`，后续可以替换为其他模型实现。

局限：

- 它是字符词元和 n-gram 有符号哈希向量，不是预训练神经 Embedding。
- 主要表达词法相似，不等同于 OpenAI 模型的深层语义空间。
- 不能把本地向量与 OpenAI 模型向量混用。

## 7. 模型维度与费用

### 本地模型

- 实际维度：384。
- float[]，每个元素必须是 finite。
- L2 归一化，范式约 1。
- API 费用：$0。
- 本地 CPU/内存资源成本未换算成金额，文档不虚构该成本。
- Provider 不返回 Token 数，因此 `inputTokens=null`、`usageStatus=NOT_APPLICABLE`。

### OpenAI 候选

- `text-embedding-3-small`：1536 维，$0.02 / 1M 输入 Token。
- `text-embedding-3-large`：3072 维，$0.13 / 1M 输入 Token。
- 两者本轮没有真实付费调用。
- 未来按官方返回的 input token 计数估算 API 费用；不能把字符数直接当作 Token。

## 8. Maven 依赖变更

本轮没有新增 Maven 依赖。

原因：

- Spring AI 1.1.8 的 `spring-ai-model` 已包含 `EmbeddingModel`、`EmbeddingRequest`、`EmbeddingResponse` 和 `Embedding`。
- 本地模型只使用 JDK 标准库和项目现有 Spring 组件。
- 没有引入 OpenAI SDK/Starter，因为本轮不执行远程付费调用。

## 9. EmbeddingModel 接入

新增本地实现：

```java
public class LocalHashNgramEmbeddingModel implements EmbeddingModel {
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) { ... }

    @Override
    public float[] embed(Document document) { ... }

    @Override
    public int dimensions() { return descriptor.dimensions(); }
}
```

向量算法：

1. Unicode NFKC 规范化。
2. 转小写并保留中英文、数字和技术符号。
3. 抽取词元、Unicode 字符 1/2/3-gram。
4. 对每个特征计算 SHA-256。
5. 使用哈希前缀映射到 384 维桶。
6. 使用哈希位决定正负号，减少单向碰撞偏差。
7. 按 L2 范数归一化。
8. 校验维度、有限数值和非零范数。

## 10. Chunk 到向量流程

`ChunkEmbeddingService.embed`：

1. 接收 D28/D29 `ChunkingResult`。
2. 逐个读取 `DocumentChunk`。
3. 拒绝 null、空串和纯空白。
4. 按模型长度单位检查最大输入。
5. 调用 `EmbeddingModel.embed(Document)`。
6. 通过统一 `RetryTemplate` 执行有限重试。
7. 校验返回 float[]。
8. 生成输入 SHA-256。
9. 保留 Chunk 的来源元数据。
10. 返回只读预览结果，不写向量库。

## 11. 向量结果校验

`EmbeddingVectorValidator` 检查：

- 向量非 null。
- 维度非零。
- 维度等于配置。
- 每个值不是 NaN。
- 每个值不是正/负 Infinity。
- 向量平方范数有限。
- 向量非零。

维度一致、数值有效和语义空间兼容是三个不同概念：

- 同文本同模型维度一致。
- 数值有限且非零不代表语义正确。
- 不同模型即使维度相同也不能混用。

## 12. 空文本与超长文本

策略：

- null：`EMBEDDING_INPUT_INVALID(4002)`。
- 空字符串：拒绝。
- 纯空白：拒绝。
- 超过配置长度：拒绝，不静默截断。
- 不丢失来源后再继续，因为超长 Chunk 应由切分阶段重新处理。

本地长度单位是 Unicode code points。未来 Token 模型必须配置可靠 Tokenizer，不能直接用字符数冒充 Token。

## 13. API Key 安全管理

配置键：

```yaml
app.embedding.api-key: ${EMBEDDING_API_KEY:}
```

安全规则：

- 默认本地模型不读取、不使用真实 Key。
- Key 只能由环境变量或外部安全配置注入。
- `EmbeddingProperties.apiKey` 标记为 Lombok `@ToString.Exclude`。
- `EmbeddingModelDescriptor` 不包含 Key。
- API 响应和错误消息不包含 Key。
- 日志不记录 Key。
- 文档与证据不记录真实 Key。
- 测试使用无效占位值。

## 14. 失败重试

复用项目唯一 `RetryTemplate` 和 `ModelFailureClassifier`。

| 失败 | 行为 |
|---|---|
| 空输入/超长输入 | 直接失败 |
| 非法 Key/参数/模型不存在 | 不重试 |
| 限流 | 有限重试 |
| 超时/连接失败 | 有限重试 |
| 502/503/504 | 有限重试 |
| 无效向量 | 响应校验失败，不重试 |

每次 Embedding 都记录尝试次数。批量结果汇总 attempts。

## 15. 模型与索引元数据

`EmbeddingModelDescriptor`：

- provider
- modelName
- modelVersion
- dimensions
- maxInputLength
- lengthUnit
- vectorSpaceId
- indexVersion
- distanceMetric
- pricePerMillionInputTokensUsd
- keyRequired
- apiUrl

API URL 本地值为 `local://hash-ngram`。云端候选元数据使用官方 endpoint，但本轮未调用。

## 16. vectorSpaceId 与索引兼容性

vectorSpaceId 计算：

```text
SHA-256(provider | modelName | modelVersion | dimensions) 前 16 字节
```

`IndexCompatibilityValidator` 要求以下全部一致：

- provider
- modelName
- modelVersion
- dimensions
- vectorSpaceId
- indexVersion
- distanceMetric

不兼容行为：

- 不同 provider：拒绝。
- 不同维度：拒绝。
- 不同模型名称：拒绝。
- 不同模型版本：拒绝。
- 同维度但不同模型：拒绝。
- 不同 indexVersion：拒绝。
- 不同 distanceMetric：拒绝。

## 17. 模型切换

配置层面支持：

```text
EMBEDDING_PROVIDER
EMBEDDING_MODEL
EMBEDDING_MODEL_VERSION
EMBEDDING_DIMENSIONS
EMBEDDING_INDEX_VERSION
EMBEDDING_DISTANCE_METRIC
EMBEDDING_API_URL
EMBEDDING_API_KEY
```

本轮可真实调用的只有本地模型。模型切换用例通过两个受控模型描述验证：

- 同维度但不同模型名称：向量空间不同，拒绝。
- 同 provider 但不同维度：拒绝。
- indexVersion 或距离度量不同：拒绝。

云端候选尚未实现远程适配器，因此没有声称真实切换调用成功。

## 18. 接口

模型元数据：

```text
GET /api/v1/embeddings/model
```

文档 Chunk 向量预览：

```text
GET /api/v1/knowledge/documents/{documentId}/embeddings?strategy=STRUCTURE_AWARE
```

该接口：

- 读取已完成文档。
- 调用 D29 Chunk preview。
- 调用当前 EmbeddingModel。
- 返回模型、索引、字段、向量和 usage/cost。
- 不持久化向量。
- 正文和向量接口从通用访问日志排除。

## 19. 关键代码文件

| 文件 | 职责 |
|---|---|
| `EmbeddingProperties.java` | 外部化模型、维度、长度、索引和密钥边界 |
| `EmbeddingModelDescriptor.java` | 无密钥模型元数据 |
| `LocalHashNgramEmbeddingModel.java` | 真实本地 Spring AI EmbeddingModel |
| `EmbeddingVectorValidator.java` | 维度和数值校验 |
| `ChunkEmbeddingService.java` | Chunk 输入、重试、元数据与结果编排 |
| `VectorSpaceIdentity.java` | 稳定 vectorSpaceId |
| `IndexCompatibilityValidator.java` | 禁止跨向量空间混用 |
| `EmbeddingController.java` | 只读模型/文档向量预览 |
| `EmbeddingModelCatalog.java` | 已核验 OpenAI 候选元数据 |
| `EmbeddingCoreSafetyTest.java` | 配置、数值和索引兼容测试 |
| `ChunkEmbeddingServiceTest.java` | 重试、输入和结果测试 |
| `RealLocalEmbeddingEvidenceTest.java` | 真实本地模型调用证据 |

## 20. 真实本地调用记录

输入来自 D29 教学知识语料：

- 中文技术短文。
- 中文技术段落。
- 中英文混合文本。
- 约 535 码点的组合长文本。

实际结果：

- Provider：LOCAL。
- Model：`local-hash-ngram-v1`。
- Dimensions：384。
- 4/4 Chunk 成功。
- 全部向量 finite、维度 384、L2 norm 约 1。
- 相同输入重复调用向量完全一致。
- 输入 hash、documentId、title、version、page 和 paragraph source 均保留。
- API 费用：$0。
- Token usage：Provider 未提供，记录为 null，没有伪造。

机器可读证据：

```text
docs/evidence/D29/D29-local-embedding-evidence-20261112.json
```

另有真实 PDF → Chunk → Embedding 集成：11 页 Canon 手册通过 D29 结构感知切分生成 17 个 Chunk，并全部生成 384 维本地向量。

## 21. 自动化测试与 Maven

覆盖：

- 正常中文、中英混合、短文本和长文本。
- 相同文本重复调用。
- 不同文本产生不同向量。
- null、空串、纯空白。
- 超长输入。
- 空向量、维度错误、NaN、Infinity。
- 限流/超时/服务端错误重试。
- 重试成功、重试耗尽。
- 非法 Key 模拟永久失败不重试。
- 不同维度。
- 同维度不同模型。
- indexVersion 和距离度量不兼容。
- vectorSpaceId 稳定性。
- Spring AI EmbeddingModel Bean 和模型信息接口。
- D28 页面结果 → Chunk → Embedding 集成。

最终全量结果见 `docs/evidence/D29/D29-embedding-test-summary-20261112.md`。

## 22. 当前边界与后续工作

已实现：

- 本地真实 EmbeddingModel。
- 中文/英文/数字基础向量化。
- 维度与数值校验。
- 输入拒绝、有限重试。
- 模型、索引和费用元数据。
- 向量空间隔离。
- 配置化模型切换契约。

尚未完成：

- OpenAI Embedding 远程适配器和真实云端调用。
- 精确 Tokenizer。
- 向量持久化与 VectorStore。
- 向量索引迁移任务。
- RAG 检索和问答。
- 预训练本地神经 Embedding 模型。

本地产出的词法向量可用于工程验证，但正式语义检索若需要更强中文语义能力，应评估云端模型或经过许可的本地神经模型，并重新生成新 vectorspace，不得复用旧索引。

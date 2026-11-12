# D29 Embedding 项目扫描记录

## 扫描范围

- `D29/pom.xml`
- `D29/D29_EBD/pom.xml`
- `knowledge/chunking`
- `knowledge/document`
- 现有模型配置、异常分类和 RetryTemplate
- Embedding 相关测试
- 未全仓库无差别读取。

## 技术版本

| 项目 | 版本 |
|---|---|
| JDK | 21.0.7 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| Maven | 3.9.16 |
| MyBatis-Plus | 3.5.17 |

## 扫描结论

- 已有 Spring AI EmbeddingModel 接口类，但没有 EmbeddingModel Bean。
- 已有 `spring-ai-starter-model-deepseek`，只用于 ChatModel，不能证明 DeepSeek 提供 Embedding。
- 项目没有 OpenAI Embedding Starter。
- 已有 ChunkingResult/DocumentChunk，可直接作为向量化输入。
- 已有 ModelFailureClassifier、RetryTemplate 和超时配置，本轮复用。
- 已有统一异常和配置属性扫描机制。
- 本轮不需要新增 Maven 依赖。

## 本轮模块

```text
com.zl.d29.knowledge.embedding
```

代码与配置扫描索引：`D29-embedding-index-20261112.md`。

# D29 真实 PDF → Chunk → Embedding 集成记录

## 链路

```text
D28/D29 PDF 页面结果
  -> D29 TextCleaner + DocumentChunker
  -> DocumentChunk
  -> LocalHashNgramEmbeddingModel
  -> EmbeddingVectorValidator
  -> EmbeddingBatchResult
```

## 输入与结果

- 输入 PDF：11 页 Canon 测试素材。
- 策略：`STRUCTURE_AWARE`。
- 实际生成 Chunk：17。
- 实际生成向量：17。
- 向量维度：每项 384。
- Provider：LOCAL。
- Model：`local-hash-ngram-v1`。
- 每个向量均通过有限值、维度、非零范数校验。
- Chunk 的 documentId、title、version、pageNumbers、sources 保留。
- API 费用：$0。
- Token usage：本地模型不返回，未伪造。

该集成由 `KnowledgeDocumentImportIntegrationTest` 和
`EmbeddingConfigurationIntegrationTest` 实际执行。

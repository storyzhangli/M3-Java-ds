# D29 Embedding 源码与 Git 索引

- 生成时间：2026-11-12 18:37:32 +08:00
- Git HEAD：ada34418bdece0ca3102d4683bc1d8bc9665cb1f
- 分支：master
- 扫描范围：D29 POM、Chunk 输出、Embedding 模型、配置、索引兼容、测试、证据与日志。

## 工作区状态

```text
 M D29/D29_EBD/src/main/java/com/zl/d29/common/GlobalExceptionHandler.java
 M D29/D29_EBD/src/main/java/com/zl/d29/common/ResultCode.java
 M D29/D29_EBD/src/main/resources/application-local.yml.example
 M D29/D29_EBD/src/main/resources/application.yml
 M D29/D29_EBD/src/test/java/com/zl/d29/document/KnowledgeDocumentImportIntegrationTest.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/ChunkEmbedding.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/ChunkEmbeddingService.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/DistanceMetric.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingBatchResult.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingController.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingCostEstimate.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingException.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingLengthUnit.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingModelCatalog.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingModelConfiguration.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingModelDescriptor.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingProperties.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingProvider.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingUsage.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingVectorValidator.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/IndexCompatibility.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/IndexCompatibilityValidator.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/LocalHashNgramEmbeddingModel.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/VectorIndexMetadata.java
?? D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/VectorSpaceIdentity.java
?? D29/D29_EBD/src/test/java/com/zl/d29/embedding/ChunkEmbeddingServiceTest.java
?? D29/D29_EBD/src/test/java/com/zl/d29/embedding/EmbeddingConfigurationIntegrationTest.java
?? D29/D29_EBD/src/test/java/com/zl/d29/embedding/EmbeddingCoreSafetyTest.java
?? D29/D29_EBD/src/test/java/com/zl/d29/embedding/LocalEmbeddingModelTest.java
?? D29/D29_EBD/src/test/java/com/zl/d29/embedding/RealLocalEmbeddingEvidenceTest.java
?? docs/evidence/D29/D29-chunking-comparison.json
?? docs/evidence/D29/D29-embedding-acceptance-20261112.md
?? docs/evidence/D29/D29-embedding-evidence-index-20261112.md
?? docs/evidence/D29/D29-embedding-final-audit-20261112.md
?? docs/evidence/D29/D29-embedding-git-diff-check-20261112.txt
?? docs/evidence/D29/D29-embedding-git-diff-check-exit-20261112.txt
?? docs/evidence/D29/D29-embedding-git-diff-name-status-20261112.txt
?? docs/evidence/D29/D29-embedding-git-diff-stat-20261112.txt
?? docs/evidence/D29/D29-embedding-git-staged-20261112.txt
?? docs/evidence/D29/D29-embedding-git-status-20261112.txt
?? docs/evidence/D29/D29-embedding-git-untracked-20261112.txt
?? docs/evidence/D29/D29-embedding-index-20261112.md
?? docs/evidence/D29/D29-embedding-jar-contents-20261112.txt
?? docs/evidence/D29/D29-embedding-jar-sha256-20261112.txt
?? docs/evidence/D29/D29-embedding-log-content-scan-20261112.txt
?? docs/evidence/D29/D29-embedding-project-scan-20261112.md
?? docs/evidence/D29/D29-embedding-runtime-integration-20261112.md
?? docs/evidence/D29/D29-embedding-sensitive-scan-20261112.md
?? docs/evidence/D29/D29-embedding-sensitive-scan-results-20261112.txt
?? docs/evidence/D29/D29-embedding-surefire-summary-20261112.txt
?? docs/evidence/D29/D29-embedding-test-summary-20261112.md
?? docs/evidence/D29/D29-index-compatibility-evidence-20261112.md
?? docs/evidence/D29/D29-local-embedding-evidence-20261112.json
?? docs/evidence/D29/D29-local-real-call-summary-20261112.md
?? docs/evidence/D29/D29-model-selection-evidence-20261112.md
?? "docs/proj_docs/D29-Embedding\346\212\200\346\234\257\345\216\237\347\220\206\344\270\216\346\250\241\345\236\213\351\200\211\346\213\251\346\223\215\344\275\234\345\256\236\350\267\265\346\214\207\345\215\227.md"
?? "docs/proj_docs/D29-Embedding\346\250\241\345\236\213\346\216\245\345\205\245\344\270\216\345\220\221\351\207\217\345\214\226\345\212\237\350\203\275\345\256\236\347\216\260\345\205\250\351\207\217\350\257\264\346\230\216.md"
```

## 关键文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingProperties.java` | `E6DC60258A443D5C7C8D40ED09AB79742031235A113CA8CA2582795D6744064D` |
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingModelDescriptor.java` | `EF13E6A5F858FBA40C60BEE60B7BABF16572615D3B5213223788EE6A75C35979` |
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/LocalHashNgramEmbeddingModel.java` | `73F2189E17CD81BF355C42E8FD91A7467C247B3F72401963DCF2C04AB25A406B` |
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingVectorValidator.java` | `6D6CE5C9288D260AF9352F4F117F00A716A7C5A01B722A0FA547A299409DF750` |
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/ChunkEmbeddingService.java` | `704147EC0F77619C0C631DDBB34C8747ACAFC74A0FD78A6D8E20A42767C48BDD` |
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/IndexCompatibilityValidator.java` | `5B57228A786A736DAF5D18AAEFE8413878A1A07BAD849D889C81C26E5DA1ADE6` |
| `D29/D29_EBD/src/main/java/com/zl/d29/knowledge/embedding/EmbeddingController.java` | `4D383D87317F660955B500BA6B502F30BD93CFA40CACD78BE08CA03A474916AB` |
| `D29/D29_EBD/src/main/resources/application.yml` | `F7052BB9A9D55BE5BE40399F3E8879BEACCA10BB5B1BBA6066EAD4D8E885E1BA` |
| `D29/D29_EBD/src/main/resources/application-local.yml.example` | `34E19F49A11B5C2844A078F3F2B07C35630C614FC65F8026989FA13EBC3674EC` |
| `D29/D29_EBD/src/test/java/com/zl/d29/embedding/LocalEmbeddingModelTest.java` | `3122357DBAA4BFF2A7099C6EC044A9A1816E366952988C4AF76C6D4A86CDC695` |
| `D29/D29_EBD/src/test/java/com/zl/d29/embedding/ChunkEmbeddingServiceTest.java` | `561B7E08B50008227AF0E060C0AB1614F69896F36DE41E7596055465B1CF742B` |
| `D29/D29_EBD/src/test/java/com/zl/d29/embedding/EmbeddingCoreSafetyTest.java` | `3ADFFD0167DCD6C06F372EF564342993EA71DB33F3E8D98D34C65F523E9F39A0` |
| `D29/D29_EBD/src/test/java/com/zl/d29/embedding/RealLocalEmbeddingEvidenceTest.java` | `43D1F8B3611B003603453FA25B4BB339730F7A5852980815DC5B76D5D8147743` |

# D28 文本清洗与 Chunk 切分源码索引

- 生成时间：2026-11-11 18:31:49 +08:00
- Git HEAD：230747e44131f825ae4c13f9f284b1b8602fd047
- 分支：master
- 扫描范围：D28 POM、页级解析结果、清洗、切分、配置、REST、测试、证据与日志。

## 工作区状态

```text
 M D28/D28_TCASS/src/main/resources/application.yml
 M D28/D28_TCASS/src/test/java/com/zl/d28/document/KnowledgeDocumentImportIntegrationTest.java
 M D28/D28_TCASS/src/test/java/com/zl/d28/document/RealPdfSourceEvidenceTest.java
 M docs/evidence/D28/D28-java-pdf-extraction.json
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkBoundaryType.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkSource.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkSourceDocument.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkStrategy.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkingComparison.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkingMetrics.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkingProperties.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkingResult.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/CleanedDocument.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/CleanedPage.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/CleanedParagraph.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/DocumentChunk.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/DocumentChunker.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/DocumentPageText.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/KnowledgeDocumentChunkingController.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/KnowledgeDocumentChunkingService.java
?? D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/TextCleaner.java
?? D28/D28_TCASS/src/test/java/com/zl/d28/chunking/ChunkingPropertiesValidationTest.java
?? D28/D28_TCASS/src/test/java/com/zl/d28/chunking/DocumentChunkerTest.java
?? D28/D28_TCASS/src/test/java/com/zl/d28/chunking/RealPdfChunkingComparisonTest.java
?? D28/D28_TCASS/src/test/java/com/zl/d28/chunking/TextCleanerTest.java
?? docs/evidence/D28/D28-chunking-acceptance-20261111.md
?? docs/evidence/D28/D28-chunking-comparison.json
?? docs/evidence/D28/D28-chunking-evidence-index-20261111.md
?? docs/evidence/D28/D28-chunking-final-audit-20261111.md
?? docs/evidence/D28/D28-chunking-jar-sha256-20261111.txt
?? docs/evidence/D28/D28-chunking-project-scan-20261111.md
?? docs/evidence/D28/D28-chunking-sensitive-scan-20261111.md
?? docs/evidence/D28/D28-chunking-test-summary-20261111.md
?? docs/evidence/D28/D28-cleaning-before-after-20261111.md
?? docs/evidence/D28/D28-strategy-comparison-20261111.md
?? "docs/proj_docs/D28-\346\226\207\346\234\254\346\270\205\346\264\227\344\270\216Chunk\345\210\207\345\210\206\345\212\237\350\203\275\345\256\236\347\216\260\345\205\250\351\207\217\350\257\264\346\230\216.md"
?? "docs/proj_docs/D28-\346\226\207\346\234\254\346\270\205\346\264\227\344\270\216Chunk\345\210\207\345\210\206\346\212\200\346\234\257\345\216\237\347\220\206\345\217\212\346\223\215\344\275\234\345\256\236\350\267\265\346\214\207\345\215\227.md"
```

## 关键文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| `D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/TextCleaner.java` | `6345CD16F18CB24DC87FFDF24410153797BE2D963004BE8232063B7D3AEC0CF1` |
| `D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/DocumentChunker.java` | `7B7993981BC6F36E26804049262234270991DD24EEA0FF5856F5EFCCA81A0638` |
| `D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/ChunkingProperties.java` | `CE9554266C5FB3F9F351DD7279C492599F3AB5AD5D6E09F6908889785084A5B4` |
| `D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/DocumentChunk.java` | `1DD877127A27C2402C21A08A6EC4F3E2F8E7B55CF9062046030B636048A80E0D` |
| `D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/KnowledgeDocumentChunkingService.java` | `E5255AF650E175BCE524EAE600E5AF75E8CB4FC2E9A2669CF02810509465FB2E` |
| `D28/D28_TCASS/src/main/java/com/zl/d28/knowledge/chunking/KnowledgeDocumentChunkingController.java` | `B999E43533A0D2F430AED5F9F2F0910970F6D79B9570E1D1E58A526AB91C27B9` |
| `D28/D28_TCASS/src/main/resources/application.yml` | `A5BBF8DE7542FF626736CD84057FC5E3FA296CCDF59D08765312FC9127B2E621` |
| `D28/D28_TCASS/src/test/java/com/zl/d28/chunking/TextCleanerTest.java` | `4D118DEC20845F7CF9899A87C7FCA5E4EB5AA53785337AEEB2D0BC8842C62C03` |
| `D28/D28_TCASS/src/test/java/com/zl/d28/chunking/DocumentChunkerTest.java` | `1CDAA3988A38B89BA77491BB6B49CD7601B018E3C2FA91AF3D2F4F544BB36B30` |
| `D28/D28_TCASS/src/test/java/com/zl/d28/chunking/ChunkingPropertiesValidationTest.java` | `151B1DB257F7702AE643D974DE824861497E28C51CE4ABCAD949C6FDB26FCA15` |
| `D28/D28_TCASS/src/test/java/com/zl/d28/chunking/RealPdfChunkingComparisonTest.java` | `28966DBC8198E8DFFF83356773A0D2B7F12F51B60DEF9BC1D15387DF54B839A7` |
| `D28/D28_TCASS/src/test/java/com/zl/d28/document/KnowledgeDocumentImportIntegrationTest.java` | `4D62D02A2A09DCC709179692261DE85DFFCF213C35E3245448383E3F0203C761` |

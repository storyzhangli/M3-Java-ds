# D28 文本清洗与 Chunk 切分测试汇总

## 结果

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 基线全量 | `mvn -f .\pom.xml test` | 217 项，0 失败，0 错误，3 跳过 | `logs/D28/D28-baseline-test.log` |
| 主代码编译 | `mvn -f .\pom.xml -DskipTests compile` | 成功 | `logs/D28/D28-compile-01.log` |
| 清洗/切分专项 | `mvn ... -Dtest=TextCleanerTest,DocumentChunkerTest,ChunkingPropertiesValidationTest` | 16/16 通过 | `logs/D28/D28-chunking-unit-tests-07.log` |
| 真实 PDF 页级解析 | `mvn ... -Dtest=RealPdfSourceEvidenceTest test` | 1/1 通过 | `logs/D28/D28-real-pdf-parser-01.log` |
| 真实 PDF 双策略 | `mvn ... -Dtest=RealPdfChunkingComparisonTest test` | 1/1 通过 | `logs/D28/D28-real-pdf-chunking-02.log` |
| 文档/Chunk 集成 | `mvn ... -Dtest=KnowledgeDocumentImportIntegrationTest test` | 13/13 通过 | `logs/D28/D28-document-chunking-integration-01.log` |
| 最终全量 | `mvn -f .\pom.xml test` | **235 项，0 失败，0 错误，1 跳过** | `logs/D28/D28-full-regression-02.log` |
| 打包 | `mvn -f .\pom.xml -DskipTests package` | 成功 | `logs/D28/D28-package-01.log` |

Surefire：69 个测试类，235 项测试，0 失败，0 错误，1 跳过。

唯一跳过项为默认禁用的真实 DeepSeek 烟雾测试，本轮没有真实模型调用。

## 新增覆盖

- CRLF、NBSP、BOM、连续空格、重复空行。
- 标题、列表和普通段落保留。
- 中文、英文、数字、单位、技术标点和组合 emoji。
- 空文本和纯空白。
- 固定长度和结构感知策略。
- Overlap 为 0、正数及非法值。
- 极短最后片段。
- 连续短段合并。
- 超长段落和超长单句。
- 跨页、跨段落来源列表。
- 同 chunkIndex 不同文档版本。
- 真实 11 页 PDF 双策略数量、长度和来源。
- 未完成文档拒绝 Chunk 预览。

# D26 测试汇总

## 命令与结果

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 基线全量 | `mvn -f .\pom.xml test` | 194 项，0 失败，0 错误，1 跳过 | `logs/D26/D26-baseline-full-test.log` |
| 上传/状态专项初轮 | `mvn -f .\pom.xml "-Dtest=KnowledgeDocumentImportIntegrationTest,KnowledgeDocumentBusinessSizeLimitTest,KnowledgeDocumentContainerSizeLimitTest,KnowledgeDocumentFileReadFailureTest" test` | 12 项中 2 项失败，暴露 MockMvc 限制与秒级时间精度 | `logs/D26/D26-document-import-tests-01.log` |
| 修复后专项 | 同上 | 12/12 通过 | `logs/D26/D26-document-import-tests-03.log` |
| 集成单类 | `mvn -f .\pom.xml -Dtest=KnowledgeDocumentImportIntegrationTest test` | 9/9 通过 | `logs/D26/D26-import-integration-final.log` |
| HTML/PDF Parser | `mvn -f .\pom.xml -Dtest=KnowledgeDocumentParserTest test` | 2/2 通过 | `logs/D26/D26-parser-tests-01.log` |
| 最终全量 | `mvn -f .\pom.xml test` | 208 项，0 失败，0 错误，1 跳过 | `logs/D26/D26-full-regression-01.log` |
| 打包 | `mvn -f .\pom.xml -DskipTests package` | 成功 | `logs/D26/D26-package-01.log` |

## 测试覆盖

| 场景 | 测试类 | 结果 |
|---|---|---|
| 登记 PENDING | `KnowledgeDocumentImportIntegrationTest` | 通过 |
| 正常文件导入/COMPLETED/内容落库 | 同上 | 通过 |
| 空文件 | 同上 | 通过 |
| 非法编码 | 同上 | 通过 |
| 不支持扩展名 | 同上 | 通过 |
| 扩展名伪装/实际 MIME 不符 | 同上 | 通过 |
| 解析失败 | 同上 | 通过 |
| PROCESSING 并发重入 | 同上 | 通过 |
| updatedAt | 同上 | 通过 |
| 重复导入与幂等 | 同上 | 通过 |
| 并发重复导入 | 同上 | 通过 |
| 业务大小限制 | `KnowledgeDocumentBusinessSizeLimitTest` | 通过 |
| Servlet Multipart 大小限制 | `KnowledgeDocumentContainerSizeLimitTest` | 通过（真实随机端口 HTTP） |
| 文件读取异常 | `KnowledgeDocumentFileReadFailureTest` | 通过 |
| HTML/PDF 基础解析 | `KnowledgeDocumentParserTest` | 通过 |

## 修复记录

1. MockMvc 不执行 Servlet multipart 上限：改用随机端口真实 HTTP 测试。
2. DATETIME 秒精度导致 updatedAt 同秒不可区分：新表使用 DATETIME(6) 并提供安全 ALTER。
3. 并发测试错误地只捕获一个 Future 的业务异常：两个 Future 都识别 COMPLETED/DOCUMENT_DUPLICATE，未减弱唯一 hash 断言。

唯一跳过项为默认禁用的真实 DeepSeek 烟雾测试。

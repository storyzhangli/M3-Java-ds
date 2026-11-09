# D27 测试汇总

## 结果

| 阶段 | 命令 | 结果 | 日志 |
|---|---|---|---|
| 基线全量 | `mvn -f .\pom.xml test` | 208 项，0 失败，0 错误，1 跳过 | `logs/D27/D27-baseline-test.log` |
| 主代码编译 | `mvn -f .\pom.xml -DskipTests compile` | 成功 | `logs/D27/D27-compile-02.log` |
| 测试编译 | `mvn -f .\pom.xml -DskipTests test-compile` | 成功 | `logs/D27/D27-test-compile-01.log` |
| 解析器专项 | `mvn -f .\pom.xml -Dtest=KnowledgeDocumentParserTest test` | 7/7 通过 | `logs/D27/D27-parser-tests-02.log` |
| 数据库/接口专项 | `mvn -f .\pom.xml -Dtest=KnowledgeDocumentImportIntegrationTest test` | 12/12 通过 | `logs/D27/D27-document-integration-01.log` |
| 真实 PDF Java 证据 | `mvn -f .\pom.xml -Dtest=RealPdfSourceEvidenceTest test` | 1/1 通过 | `logs/D27/D27-real-pdf-evidence-01.log` |
| 最终全量 | `mvn -f .\pom.xml test` | **217 项，0 失败，0 错误，1 跳过** | `logs/D27/D27-full-regression-01.log` |
| 打包 | `mvn -f .\pom.xml -DskipTests package` | 成功 | `logs/D27/D27-package-01.log` |

Surefire 汇总：65 个测试类，217 项测试，0 失败，0 错误，1 跳过。唯一跳过项为默认禁用的真实 DeepSeek 烟雾测试，本轮未调用真实模型。

## 新增覆盖

| 场景 | 结果 |
|---|---|
| 正常 PDF 页面提取 | 通过 |
| 多页 PDF 物理页码 1 到 N | 通过 |
| 中间空白页保留 | 通过 |
| 零页 PDF | 正确失败 |
| 全部空白 PDF | 正确失败 |
| 损坏 PDF | 正确失败 |
| 图片型扫描 PDF | 识别无文本并提示 OCR |
| 中文/英文/数字混合 PDF | 通过 |
| DOCX 整文提取 | 通过 |
| documentId 页面关联 | 通过 |
| source/title/version 保留 | 通过 |
| 页面接口未完成状态拒绝 | 通过 |
| 真实 11 页 Canon PDF | 通过 |
| 不支持格式和扩展名伪装 | 原有测试继续通过 |
| 文件大小限制和非法编码 | 原有测试继续通过 |
| PROCESSING 重入和重复导入 | 原有测试继续通过 |

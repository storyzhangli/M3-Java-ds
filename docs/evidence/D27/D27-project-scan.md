# D27 项目扫描记录

## 范围

- 根 POM：`M3-Java-ds/D27/pom.xml`
- 模块 POM：`M3-Java-ds/D27/D27_PDF_WORD/pom.xml`
- 直接相关源码：`knowledge/document`、统一异常、访问日志配置和迁移脚本。
- 未进行全仓库无差别读取。

## 技术环境

| 项目 | 实际值 |
|---|---|
| JDK | 21.0.7 |
| Maven | 3.9.16 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| MyBatis-Plus | 3.5.17 |
| Apache Tika | 3.3.1 |
| Apache PDFBox | 3.0.7 |
| Apache POI | 5.5.1（Tika 传递） |

## 原有能力

- 已有 D26 式 `knowledge_document` 元数据表和导入状态。
- 已有 `knowledge_document_content` 原始字节及兼容整文解析文本。
- 已有登记、导入、详情三个入口和 `PENDING/PROCESSING/COMPLETED/FAILED` 状态机。
- 已有 Tika MIME 检测、UTF-8 校验、大小校验、SHA-256 去重和统一异常映射。
- 已有 Spring AI PDF Reader，其实际传递 PDFBox 3.0.7。

## 扫描结论

- 原 PDF 文本使用 Tika 整文读取，没有页面级结果结构，无法满足页码追溯。
- `spring-ai-pdf-document-reader:1.1.8` 已提供 PDFBox 3.0.7，无需引入第二套 PDF 引擎。
- Tika 3.3.1 已传递 Apache POI 5.5.1，可支持 DOCX 可选整文提取。
- 既有数据库迁移采用可重复执行的 `CREATE TABLE IF NOT EXISTS` 和显式时间精度修正。

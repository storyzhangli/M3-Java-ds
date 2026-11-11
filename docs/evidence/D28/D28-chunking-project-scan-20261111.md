# D28 项目扫描记录

## 范围

- 根 POM：`D28/pom.xml`
- 模块 POM：`D28/D28_TCASS/pom.xml`
- 文本相关代码：`knowledge/document`、`knowledge/chunking`
- 配置：`application.yml`
- 测试：`src/test/java/com/zl/d28/document`、`chunking`
- 未全仓库无差别读取。

## 环境

| 项目 | 实际值 |
|---|---|
| JDK | 21.0.7 |
| Spring Boot | 3.5.16 |
| Spring AI | 1.1.8 |
| Apache PDFBox | 3.0.7 |
| Apache Tika | 3.3.1 |
| MyBatis-Plus | 3.5.17 |

## 现有能力

- D28 已继承 D27 的 PDFBox 逐页解析。
- `knowledge_document` 保存 documentId、来源、标题、版本与状态。
- `knowledge_document_page` 保存物理页码、页面文本、字符数、空白页和解析器版本。
- 导入服务支持完成状态、失败状态和页面结果事务。
- 当前没有正式文本清洗或 Chunk 生成实现。

## 本轮复用

- 复用 `KnowledgeDocumentPage` 作为真实切分输入。
- 复用 `KnowledgeDocumentRepository` 查询完整文档和页面顺序。
- 复用 `KnowledgeDocumentException` 和既有业务错误码。
- 新增切分组件独立于数据库、Embedding 和 VectorStore。

# D26 现状与 Schema 复用

## 版本与模块

- Git HEAD：`d144ad4b3369f0b83a3ae1a5d918d22f9348366e`
- 分支：`master`
- 模块：`D26(pom) -> D26_DIABP(jar)`
- JDK 21
- Spring Boot 3.5.16
- Spring AI 1.1.8
- MyBatis-Plus 3.5.17
- Tika 3.3.1
- PDFBox 3.0.7

## 原有能力

- 已有统一 `Result`、全局异常处理。
- 已有 MyBatis-Plus Mapper/Repository/PO 模式和 MySQL。
- 已有 Markdown/PDF/HTML/Tika Reader 依赖，无需新增依赖。
- 已有 classpath 知识片段检索，但没有 Document 实体/Repository/表/上传接口。
- 当前只有开发身份 Header，无生产鉴权。

## D25 Schema 复用

复用：

- documentId
- sourceSystem/source
- title
- version
- status
- updatedAt
- contentSha256

最小调整：

- D25 的 `status` 是发布生命周期；D26 任务要求 PENDING/PROCESSING/COMPLETED/FAILED 导入状态。本轮 `status` 专用于导入状态。
- 不伪造 D25 的 tenant/plant/ACL/适用范围/生效时间。
- 原始字节与解析文本存 `knowledge_document_content`，元数据存 `knowledge_document`。
- 文件内容哈希字段 `content_hash` 唯一。

## 新增持久化

- `knowledge_document`
- `knowledge_document_content`

DDL：`src/main/resources/db/schema-knowledge-document.sql`。

应用启动不会自动执行该 DDL；部署/运行前需显式应用到目标 MySQL。测试通过 `@Sql` 创建。

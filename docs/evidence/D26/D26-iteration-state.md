# D26 迭代状态

## 需求编号

- D26-R01 知识文档登记
- D26-R02 文件导入与基础解析
- D26-R03 状态流转与更新时间
- D26-R04 服务端安全校验
- D26-R05 contentHash 重复与并发控制

## 新增/修改文件

### 配置/迁移

- `src/main/resources/application.yml`
- `src/main/resources/db/schema-knowledge-document.sql`

### 领域/持久化/DTO

- `knowledge/document/KnowledgeDocument.java`
- `knowledge/document/KnowledgeDocumentStatus.java`
- `knowledge/document/KnowledgeImportProperties.java`
- `knowledge/document/po/KnowledgeDocumentPO.java`
- `knowledge/document/po/KnowledgeDocumentContentPO.java`
- `knowledge/document/mapper/KnowledgeDocumentMapper.java`
- `knowledge/document/mapper/KnowledgeDocumentContentMapper.java`
- `knowledge/document/repository/KnowledgeDocumentRepository.java`
- `knowledge/document/repository/MybatisKnowledgeDocumentRepository.java`
- `knowledge/document/dto/KnowledgeDocumentRegistrationRequest.java`
- `knowledge/document/dto/KnowledgeDocumentView.java`

### 业务/入口

- `knowledge/document/KnowledgeDocumentException.java`
- `knowledge/document/KnowledgeDocumentParser.java`
- `knowledge/document/KnowledgeDocumentService.java`
- `knowledge/document/KnowledgeDocumentController.java`
- `common/ResultCode.java`
- `common/GlobalExceptionHandler.java`

### 测试

- `KnowledgeDocumentImportIntegrationTest.java`
- `KnowledgeDocumentBusinessSizeLimitTest.java`
- `KnowledgeDocumentContainerSizeLimitTest.java`
- `KnowledgeDocumentFileReadFailureTest.java`
- `KnowledgeDocumentParserTest.java`

## 设计决定

- 登记与导入分离：登记只写元数据；导入才读文件和解析。
- 服务端生成 documentId。
- 元数据与原始内容分表，documentId 关联。
- 短事务：登记、抢占 PROCESSING、完成、失败各自独立。
- 解析不持有数据库事务。
- `content_hash` 唯一约束 + 导入前 hash 查询防止重复处理。
- Key 文件元数据、原始字节和解析文本入库；本轮不切 Chunk。
- 当前 `status` 语义为导入状态；D25 发布生命周期/ACL 后续扩展。

## 测试

- 基线：194 项。
- 新增 5 类 14 项测试。
- 最终全量：208 项，0 失败，0 错误，1 跳过。
- 打包成功。

## 证据

- 测试摘要：`D26-test-summary.md`
- 上传安全矩阵：`D26-upload-security-matrix.md`
- Git Diff：`D26-git-diff.patch`
- 原始日志：`logs/D26`

## 遗留问题

- D25 完整发布生命周期和知识 ACL 尚未进入 D26。
- 容器层拒绝发生在调用业务前，文档保持 PENDING；客户端需修正文件后重试。
- 进程崩溃留下的 PROCESSING 尚无自动恢复扫描；异常路径本身已正确收口。
- 未实现 Chunk/Embedding/VectorStore/RAG。

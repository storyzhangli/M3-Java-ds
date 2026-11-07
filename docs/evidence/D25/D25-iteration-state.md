# D25 迭代状态

## 需求编号

- D25-R01 KnowledgeDocument、Chunk 元数据与 RAG 模块边界
- D25-R02 固定问题集与期望证据

## 新增/修改文件

### 设计/证据

- `D25-baseline.md`
- `D25-knowledge-document-schema.json`
- `D25-knowledge-document-schema.md`
- `D25-chunk-schema.json`
- `D25-chunk-schema.md`
- `D25-rag-module-boundary.md`
- `D25-rag-evaluation-dataset.json`
- `D25-rag-evaluation-schema.json`
- `D25-evaluation-case-index.md`
- `D25-validation-summary.md`

### 临时脚本

- `D25-index-scan.ps1`
- `D25-validate-evaluation.ps1`

### 生产代码

无。本轮是架构与评测设计，不新增业务代码、依赖或数据库表。

## 设计决定

- KnowledgeDocument 使用稳定 documentId + 不可变 version 快照。
- Chunk 通过 documentId/documentVersion/chunkGeneration 与父版本关联。
- 权限通过 permissionPolicyId 引用权威 ACL；可见性标签本身不是授权结论。
- ACL 必须在证据返回前 filter，不可由模型判断。
- 重新切分、Embedding 和索引使用独立版本；旧 Chunk/向量/缓存 STALE。
- 当前关键词检索作为 Retrieval Adapter；未来向量实现不进入领域模型。
- RAG 逻辑边界以包/接口设计，不拆 Maven 模块。
- 评测集固定 21 条，缺资料项不虚构文档/页码/Chunk。

## 评测数据

- 总用例：21
- answerable：7
- fault-code：4
- unanswerable：4
- permission：4
- boundary：2
- 当前真实语料：3 文档、8 Chunk
- 权限、故障码、版本和完整 ACL 资料待补。

## 校验/测试

- 数据集 JSON Schema：通过
- caseId/问题唯一：通过
- 类别最低数量：通过
- 真实 Document/Chunk 引用：通过
- 项目全量测试：194 项，0 失败，0 错误，1 跳过
- RAG 端到端运行评测：未执行

## 证据

- 索引：`D25-baseline-index.md`
- 数据集：`D25-rag-evaluation-dataset.json`
- 校验摘要：`D25-validation-summary.md`
- 元数据字典：Document/Chunk schema 文件
- 边界：`D25-rag-module-boundary.md`
- 日志：`logs/D25/D25-baseline-full-test.log`、`logs/D25/D25-dataset-validation.log`

## 遗留问题

- 真实知识文档尚无版本、ACL、设备型号和故障码字典。
- 权限用例在当前代码中无法端到端执行，因为无知识权限实现。
- Embedding/向量库/解析器未实现，RAG 指标无实际数值。

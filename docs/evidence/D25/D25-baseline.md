# D25 仓库现状基线

## 版本与模块

- Git HEAD：`824de41d788a4958eec802cd905245c6eb5a9b32`
- 分支：`master`
- 模块：`D25(pom) -> D25_DKDCM_RAC(jar)`
- JDK 21
- Spring Boot 3.5.16
- Spring AI 1.1.8
- Maven 3.9.16

## 现状简表

| 检查项 | 实际状态 | 证据位置 | 对 D25 的影响 |
|---|---|---|---|
| 模块与依赖 | 单 JAR 模块；已有 Web/MySQL/Redis/Spring AI | `D25/pom.xml`、模块 `pom.xml` | 不新增模块/依赖 |
| 文档与文件能力 | 只有 classpath 预切分教学语料；无上传/解析器/文件存储 | `knowledge/equipment-fault-corpus.json`、`KnowledgeCorpus` | Document/Chunk 需先设计契约 |
| 用户与权限 | 只有 `X-Dev-User` 开发归属；无知识 ACL/生产身份 | `HistoryProperties`、`ChatHistoryService` | 权限评测标记待业务确认 |
| 持久化能力 | MySQL 有会话、消息、Prompt 模板；无 KnowledgeDocument/Chunk 表 | `db/schema-chat-history.sql`、`schema-prompt-template.sql` | 不新增表，本轮只设计 |
| AI/RAG 能力 | 字符 bigram 关键词检索；无 Embedding/向量库/重排 | `KnowledgeRetrievalService` | 当前不是完整 RAG |
| 证据与引用 | `CitationRegistry` 只允许真实检索 Chunk 的请求级引用 | `CitationRegistry` | 可复用为后续 RAG 输出边界 |
| 测试体系 | JUnit 5/Spring Boot Test/MockMvc，现有检索/引用/历史测试 | `src/test/java` | 评测集用 JSON/脚本验证 |

## 真实知识语料

- 文件：`D25/D25_DKDCM_RAC/src/main/resources/knowledge/equipment-fault-corpus.json`
- 文档数：3
- Chunk 数：8
- 文档：
  - `DOC-CNC-COOLING-001`
  - `DOC-SERVO-TROUBLE-002`
  - `DOC-WORKORDER-SPEC-003`
- 缺少：显式文档版本、生效/失效时间、租户/工厂/组织 ACL、设备型号绑定、故障码字典、Embedding/索引状态。

## 现有 KnowledgeChunk

当前字段：

```text
documentId, chunkId, title, sourceType, section, page, excerpt, url
```

这是检索夹具，不是完整 D25 Chunk 目标模型。D25 设计会补齐版本、定位、权限继承、哈希、索引/向量状态等，但不修改生产代码。

## 当前检索边界

```text
KnowledgeCorpus
 -> KnowledgeRetrievalService (bigram keyword)
 -> CitationRegistry
 -> KnowledgeChatOrchestrator
 -> AiChatService
```

- 检索无命中返回空列表。
- 引用编号由服务端分配。
- 模型自造引用会被删除。
- 没有权限过滤，因为当前没有知识 ACL 实现。

## 本轮结论

本轮适合“设计 + 评测基线”，不适合直接实现完整 RAG 链路。真实 RAG 端到端评测状态为“未执行”。

## 构建验证

- 全量命令：`mvn -f .\pom.xml test`
- 结果：194 个测试，0 失败，0 错误，1 跳过
- 跳过项：默认禁用的真实 DeepSeek 烟雾测试
- 打包命令：`mvn -f .\pom.xml -DskipTests package`
- 打包结果：成功
- 日志：`logs/D25/D25-baseline-full-test.log`、`logs/D25/D25-package-01.log`

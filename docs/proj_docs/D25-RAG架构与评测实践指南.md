# D25 RAG 架构与评测实践指南

## 1. 目标

本指南用于：

- 统一 KnowledgeDocument/Chunk 语义。
- 给出 RAG 十个职责的边界。
- 明确权限继承、版本和失效策略。
- 提供固定可重复问题集。
- 为后续 Chunk、Embedding、检索、重排和 Prompt 变更建立回归基线。

当前项目只有关键词检索和教学语料，本指南描述目标设计，不把后续规划当作已实现能力。

## 2. 五类对象

| 对象 | 含义 |
|---|---|
| KnowledgeDocument | 原始资料业务身份、来源、版本、权限和生命周期 |
| Chunk | 从 DocumentVersion 切出的检索单元 |
| Embedding | 某个 Chunk 在特定模型/版本下的向量 |
| RetrievalResult | 经权限和元数据过滤后的候选证据 |
| GeneratedAnswer | 模型基于授权证据生成的答案 |

不能混用这五类对象。

## 3. Document 元数据

至少包含：

- 稳定 ID：documentId
- 标题、类型、来源、文件标识、存储定位
- 不可变版本 version
- 状态/生命周期
- 生效/失效时间
- 租户/工厂/组织范围
- 可见性和权限策略 ID
- 语言、摘要、内容哈希、解析状态
- 设备型号/系列和适用范围
- 扩展属性

字段字典：

```text
docs/evidence/D25/D25-knowledge-document-schema.md
```

机器契约：

```text
docs/evidence/D25/D25-knowledge-document-schema.json
```

## 4. Chunk 元数据

必须可追溯：

- chunkId
- documentId + documentVersion
- chunkGeneration + chunkIndex
- 文本与哈希
- 页码/章节/字符范围
- parser/chunker/index 版本
- 状态
- 父权限策略和版本
- 受控业务标签
- 真实 tokenCount
- Embedding 规划状态

字段字典：

```text
docs/evidence/D25/D25-chunk-schema.md
```

机器契约：

```text
docs/evidence/D25/D25-chunk-schema.json
```

## 5. 版本策略

### 5.1 发布版本不可变

```text
documentId 稳定
documentVersion 不可变
```

文档内容修改必须创建新版本；不能原地覆盖已发布版本。

### 5.2 重切分

`chunkerVersion` 或切分规则变化：

```text
新 chunkGeneration
 -> 新 chunkId
 -> 新索引
 -> 验证后切换
 -> 旧 generation STALE
```

### 5.3 重嵌入

Embedding 模型/维度变化：

- Chunk 文本可不变。
- 生成新向量版本。
- 旧向量 STALE。
- 不改变 Document/Chunk 稳定身份。

### 5.4 删除/归档

- 在线检索立即排除。
- 检索缓存失效。
- 向量/文件异步清理。
- 保留最小 tombstone 以阻止旧索引复活。

## 6. 权限设计

### 6.1 权限来源

Document 保存：

- tenantId
- plantScopes
- orgScopes
- visibility
- permissionPolicyId
- permissionPolicyVersion

实际授权由身份/权限服务根据当前用户和策略判定。

`visibility=INTERNAL` 或标签不能替代 ACL。

### 6.2 执行顺序

```text
身份认证
 -> 构造用户上下文
 -> 权限服务解析策略
 -> metadata + ACL filter
 -> 检索
 -> 应用层二次 ACL 校验
 -> Context Assembly
 -> Generation
```

文档是否可见、标题、数量、Chunk ID 都属于敏感信息；拒绝时不得泄露存在性。

### 6.3 Chunk 继承

- Chunk 使用父 Document 的 permissionPolicyId。
- Chunk 权限不得比父文档宽。
- 权限变化实时通过 version 生效。
- 缓存 key 必须包含 permissionPolicyVersion。

### 6.4 当前限制

当前应用只有 `X-Dev-User` 开发归属，不是生产认证/知识 ACL。因此 4 条权限问题用例期望 deny，但 `sourceStatus=待业务确认`，本轮不宣称已通过权限运行测试。

## 7. RAG 职责边界

| 职责 | 目标输出 |
|---|---|
| Ingestion | DocumentVersion |
| Parsing | 结构化内容与定位 |
| Chunking | Chunk generation |
| Metadata | 版本、ACL、业务标签 |
| Embedding | 向量与模型版本 |
| Vector Store | 向量读写/删除 |
| Retrieval | 权限过滤后的候选 |
| Context Assembly | 可追溯证据上下文 |
| RAG Generation | Answer + citations |
| Evaluation | 固定问题集与指标 |

当前状态：

- Ingestion/Parsing/Chunking 只有预切分教学夹具。
- Retrieval 是关键词实现。
- Embedding/Vector Store/ACL 未实现。
- Context/Citation 和通用生成已有。

依赖方向：

```text
Controller -> Application -> Ports/Domain
Adapter -> Ports/Domain
```

供应商 SDK、向量数据库客户端不能进入领域对象或业务接口。

详见 `D25-rag-module-boundary.md`。

## 8. 评测集

### 8.1 数据

```text
docs/evidence/D25/D25-rag-evaluation-dataset.json
```

21 条：

- answerable 7
- fault-code 4
- unanswerable 4
- permission 4
- boundary 2

### 8.2 每条用例

固定字段：

- caseId
- category
- question
- userContext
- expectedDecision
- expectedAnswerPoints
- expectedEvidence
- forbiddenClaims
- acceptanceCriteria
- sourceStatus

### 8.3 来源原则

有答案用例只引用当前真实文档/Chunk：

- `DOC-CNC-COOLING-001-C01/C02/C03`
- `DOC-SERVO-TROUBLE-002-C01/C02`
- `DOC-WORKORDER-SPEC-003-C01/C02`

当前语料没有文档版本，因此证据中的 `documentVersion=null`、`versionStatus=UNRECORDED`，不虚构版本。

故障码、价格、噪声、电气参数、权限和跨型号问题均标记缺资料/待确认。

## 9. 数据集校验

运行：

```powershell
& E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs\D25\D25-validate-evaluation.ps1
```

校验：

- JSON Schema
- 至少 20 条
- caseId/问题唯一
- 类别最低数量
- 权限用例不输出证据 ID
- expectedEvidence 的 document/chunk 必须存在于真实语料

结果：21 条全部通过，RAG 运行评测未执行。

## 10. 指标口径

后续实际评测可计算：

### Decision Accuracy

```text
正确 answer/abstain/deny/clarify 数 / 总用例数
```

### Evidence Recall

```text
召回且实际引用的期望 Chunk 数 / 期望 Chunk 总数
```

### MRR

```text
每条用例首个相关 Chunk 排名倒数的均值
```

### Answer Point Coverage

```text
覆盖的 expectedAnswerPoints 数 / expectedAnswerPoints 总数
```

### Forbidden Claim Rate

```text
出现 forbiddenClaims 的用例数 / 总用例数
```

### Permission Leak Count

```text
无权请求返回任何证据或存在性信息的次数
```

当前没有运行完整 RAG，不填写数值。

## 11. 实际评测流程

1. 固定语料、Document/Chunk 版本和数据集版本。
2. 以固定用户上下文执行检索/生成。
3. 保存决策、候选 Chunk、引用和答案。
4. 检查 decision。
5. 检查 answer points。
6. 检查 evidence 真实、版本正确、支持结论。
7. 检查 forbidden claims。
8. 检查权限是否在证据暴露前拦截。
9. 计算指标。
10. 保存运行 ID、代码版本、索引版本、模型/Prompt 版本。

缺少任一项时不得比较历史分数。

## 12. 常见问题

### 为什么有答案问题只有 7 条？

当前真实教学语料只有 3 文档/8 Chunk。只对实际能支持的问题给 `answer`，其余不编造。

### 为什么权限用例没有文档 ID？

当前没有真实受限知识 ACL 夹具。给权限用例编造受限文档 ID 会违反证据真实性。后续接入真实权限夹具后补充。

### 为什么故障码问题期望拒答？

当前没有型号/版本绑定的故障码字典。不能从常识或其他型号推断。

### 关键词检索是否算完整 RAG？

不算。它是当前 Retrieval Adapter，缺少解析、通用切分、Embedding、向量库、重排和 ACL。

## 13. 后续实现顺序

1. 补齐真实 DocumentVersion 与 Chunk 持久化。
2. 接入身份/权限服务和 ACL 过滤。
3. 增加 2 组权限夹具与版本化故障码资料。
4. 实现 Parser/Chunker。
5. 选择 Embedding 与 Vector Store。
6. 在权限过滤之上实现召回和重排。
7. 接入 Context Assembly/Generation。
8. 运行 21 条数据集并记录实际指标。

## 14. 证据

- `D25-baseline.md`
- `D25-knowledge-document-schema.md`
- `D25-chunk-schema.md`
- `D25-rag-module-boundary.md`
- `D25-rag-evaluation-dataset.json`
- `D25-evaluation-case-index.md`
- `D25-validation-summary.md`
- `D25-acceptance-matrix.md`

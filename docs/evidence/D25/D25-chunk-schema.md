# D25 Chunk 元数据字典

## 1. 概念

Chunk 是从一个不可变 DocumentVersion 切分出的检索单元。它保留稳定父子关联、定位、版本、权限和索引状态。

Chunk 不等于 Embedding；同一 Chunk 可以因模型升级产生多个向量版本。

## 2. 字段

| 字段 | 类型 | 必填 | 含义 | 来源 | 约束 |
|---|---|---|---|---|---|
| `chunkId` | string | 是 | Chunk 稳定标识 | Chunker 确定性生成 | 唯一；建议含 document/version/generation/index |
| `documentId` | string | 是 | 父文档 | 父 Document | 联合索引 |
| `documentVersion` | string | 是 | 父文档不可变版本 | 父 Document | `(documentId,version)` 必须存在 |
| `chunkGeneration` | integer | 是 | 重切分代次 | Chunking | ≥1；索引/缓存键 |
| `chunkIndex` | integer | 是 | 文档内顺序 | Chunker | ≥0；generation 内唯一 |
| `text` | string | 是 | 检索/证据文本 | 解析与切分 | 非空；≤20000；不可信数据 |
| `textSha256` | string | 是 | 片段文本哈希 | 系统计算 | SHA-256 |
| `language` | BCP-47 | 是 | 语言 | 解析/审核 | 检索可选过滤 |
| `sectionPath` | string[] | 否 | 章节层级 | Parser | 保留层级顺序 |
| `pageStart/pageEnd` | integer/null | 否 | 页码范围 | Parser | 页码真实存在才填 |
| `charStart/charEnd` | integer/null | 否 | 原文字符偏移 | Parser | 0≤start≤end |
| `sourceLocator` | string/null | 否 | 其他定位 | Parser | 不得包含本机绝对路径 |
| `parserVersion` | string | 是 | 解析器版本 | 系统 | 复现/重解析 |
| `chunkerVersion` | string | 是 | 切分器版本 | 系统 | 重切分触发 |
| `indexVersion` | string | 是 | 索引 schema/生成版本 | 系统 | 查询兼容 |
| `status` | enum | 是 | ACTIVE/STALE/FAILED/DELETED | 生命周期 | 只有 ACTIVE 可检索 |
| `permissionPolicyId` | string | 是 | 继承父文档 ACL 引用 | 父 Document | 禁止扩大父权限 |
| `documentPermissionVersion` | integer | 是 | 父 ACL 版本快照 | 父 Document | 与实时权限版本比较 |
| `visibilitySnapshot` | enum | 建议 | 父可见性快照 | 父 Document | 仅辅助审计 |
| `deviceModels` | string[] | 否 | 型号标签 | 源内容/审核 | 可确认才填写 |
| `faultCodes` | string[] | 否 | 故障码标签 | 版本化字典 | 禁止猜测 |
| `components` | string[] | 否 | 部件标签 | 源内容/审核 | 受控字典 |
| `repairCategories` | string[] | 否 | 维修类别 | 源内容/审核 | 受控字典 |
| `tokenCount` | integer/null | 否 | Token 数 | 实际 tokenizer | 未计算必须 null |
| `embedding` | object/null | 否 | 向量记录摘要 | Embedding/Vector Store | 见下 |
| `createdAt` | datetime | 是 | 创建时间 | 系统 | 不可变 |
| `indexedAt` | datetime/null | 否 | 成功索引时间 | 系统 | 索引成功后填写 |
| `metadata` | object | 否 | 受控标量扩展 | 接入 | 不得放 ACL 密钥/个人信息 |

## 3. Embedding 子对象（后续规划）

| 字段 | 类型 | 含义 |
|---|---|---|
| `model` | string | Embedding 模型标识 |
| `dimensions` | integer | 实际维度 |
| `vectorRecordId` | string | 向量库记录 ID |
| `status` | enum | PENDING/INDEXED/FAILED/STALE |

本轮没有 Embedding/向量库实现，状态为后续规划。

## 4. 父子关系

```text
KnowledgeDocument(documentId, version)
  1 ─── N Chunk(documentId, documentVersion, chunkGeneration, chunkIndex)
  1 ─── N ChunkEmbedding(chunkId, model, embeddingVersion)
```

DocumentVersion 不可变。重切分产生新的 `chunkGeneration`，不原地覆盖旧 Chunk。

## 5. 权限继承

- Chunk 必须继承父 Document 的 `permissionPolicyId`。
- Chunk 不得声明比父文档更宽的权限。
- 检索前或向量查询阶段必须结合当前用户上下文过滤。
- 支持向量库元数据 pre-filter 时优先；仍需应用层 post-filter 兜底。
- 权限不足时不得返回 chunkId、标题、摘要或任何证据内容。

## 6. 版本与失效

| 事件 | 处理 |
|---|---|
| 文档发布新版本 | 新 generation 建索引，旧 ACTIVE→STALE |
| chunkerVersion 升级 | 重切分新 generation |
| parserVersion 升级 | 重新解析并比对内容哈希 |
| Embedding 模型升级 | 新向量版本；旧向量 STALE |
| indexVersion 升级 | 重建索引并版本化缓存键 |
| 文档归档/删除 | 在线检索排除；异步清向量/缓存；保留 tombstone |
| 权限策略升级 | 权限版本变化，实时重新判定；不需要重嵌入 |

## 7. 去重

- Document 级：`tenantId + contentSha256` 可识别重复原件。
- Chunk 级：`documentId + documentVersion + chunkGeneration + chunkIndex` 唯一。
- 文本重复：`textSha256` 用于诊断，不跨权限域自动合并。
- 不可把不同 tenant/ACL 的相同文本合并为一个证据。

## 8. 检索与过滤职责

- 元数据过滤：租户、状态、有效期、设备/故障码等硬条件。
- ACL 过滤：独立权限服务最终判定，不能仅依靠检索标签。
- 向量检索：相似度召回。
- 重排：在已通过权限过滤的候选内进行。
- 上下文组装：保留可追溯到 document/version/chunk 的证据。

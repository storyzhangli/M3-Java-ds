# D25 KnowledgeDocument 元数据字典

## 1. 概念

`KnowledgeDocument` 表示原始知识资料及其业务身份、来源、版本、有效期、权限和生命周期。

它不是 Chunk、Embedding、检索结果或模型回答。

## 2. 字段

| 字段 | 类型 | 必填 | 含义 | 来源 | 可变性 | 约束/索引建议 |
|---|---|---|------|---|--------|---------------|
| `documentId` | string | 是 | 跨版本稳定业务标识 | 接入系统生成 | 不可变 | 全局唯一；主查询键 |
| `title` | string | 是 | 文档标题 | 源文档/人工审核 | 可变更，新版本生效 | 长度≤300；全文/前缀索引按查询需求 |
| `documentType` | enum | 是 | manual/guide/spec/fault_code_catalog/work_order/other | 审核后确定 | 新版本可变 | 分类过滤索引 |
| `sourceSystem` | string | 是 | 来源系统 | 接入配置 | 不可变 | 长度≤64；与 sourceFileId 联合索引 |
| `sourceFileId` | string | 是 | 来源系统内原始标识 | 来源系统 | 不可变 | 与 sourceSystem 唯一 |
| `fileName` | string | 是 | 原始文件名 | 来源元数据 | 不可变 | 长度≤255 |
| `storageUri` | string | 建议 | 内部对象存储定位 | 文件存储层 | 不可变 | internal/https/s3 URI；禁止本机绝对路径 |
| `version` | string | 是 | 文档版本快照 | 发布流程 | 单版本内不可变 | `(documentId,version)` 唯一 |
| `status` | enum | 是 | DRAFT/IN_REVIEW/PUBLISHED/ARCHIVED | 生命周期服务 | 受控流转 | 状态索引 |
| `effectiveFrom` | datetime | 否 | 生效时间 | 业务配置 | 发布前可改 | 时间索引 |
| `effectiveUntil` | datetime | 否 | 失效时间 | 业务配置 | 受控修改 | 必须晚于生效时间 |
| `tenantId` | string | 是 | 租户范围 | 身份/租户配置 | 不可跨租户移动 | 强制过滤索引 |
| `plantScopes` | string[] | 是 | 工厂范围 | 权限策略/资料属性 | 策略版本化 | 数组/关联表；过滤索引 |
| `orgScopes` | string[] | 否 | 组织范围 | 权限策略 | 策略版本化 | 过滤索引 |
| `visibility` | enum | 是 | PUBLIC/INTERNAL/RESTRICTED | 审核/权限策略 | 策略版本化 | 不作为唯一授权依据 |
| `permissionPolicyId` | string | 是 | 权威 ACL 策略引用 | 权限服务 | 策略版本化 | 权限查询索引；非标签 |
| `permissionPolicyVersion` | integer | 建议 | ACL 版本 | 权限服务 | 增加 | 缓存/失效版本键 |
| `language` | BCP-47 | 是 | 文档语言 | 解析/审核 | 可修正 | 语言过滤 |
| `summary` | string/null | 否 | 审核摘要 | 解析/审核 | 可变更 | ≤1000；不得替代原文证据 |
| `contentSha256` | string | 是 | 原始内容哈希 | 文件接入计算 | 版本内不可变 | SHA-256；去重索引 |
| `parseStatus` | enum | 是 | PENDING/PARSING/PARSED/FAILED | 解析服务 | 受控流转 | 只允许 PARSED 进入切分 |
| `deviceFamilies` | string[] | 否 | 设备系列 | 源内容/审核 | 新版本可变 | 受控字典 |
| `deviceModels` | string[] | 否 | 设备型号 | 源内容/审核 | 新版本可变 | 不得从标题猜测 |
| `applicability` | string/null | 否 | 适用范围说明 | 源内容/审核 | 新版本可变 | ≤1000 |
| `createdAt` | datetime | 是 | 首次创建时间 | 系统 | 不可变 | 时间索引 |
| `updatedAt` | datetime | 是 | 元数据更新时间 | 系统 | 自动更新 | 增量同步 |
| `submittedAt` | datetime/null | 否 | 送审时间 | 生命周期服务 | 事件 | 审计 |
| `reviewedAt` | datetime/null | 否 | 审核完成时间 | 生命周期服务 | 事件 | 审计 |
| `publishedAt` | datetime/null | 否 | 发布时间 | 生命周期服务 | 发布后不可变 | 生效筛选 |
| `archivedAt` | datetime/null | 否 | 归档时间 | 生命周期服务 | 归档时写入 | 失效筛选 |
| `createdByPrincipalId` | string | 建议 | 创建者主体 ID | 身份服务 | 不可变 | 不存姓名/敏感资料 |
| `reviewedByPrincipalId` | string/null | 否 | 审核主体 ID | 身份服务 | 事件 | 不存姓名 |
| `extensionProperties` | object | 否 | 受控扩展标量 | 具体接入 | 版本化 | 禁止嵌套敏感数据和 ACL 绕过 |

完整机器契约：`D25-knowledge-document-schema.json`。

## 3. 生命周期

```text
DRAFT
 -> IN_REVIEW
 -> PUBLISHED
 -> ARCHIVED
```

- `parseStatus=FAILED` 不得进入切分/索引。
- 只有 PUBLISHED、有效期正确且权限通过的版本可被检索。
- 发布后内容修改必须创建新 `version`，不可原地覆盖。
- ARCHIVED 版本不再被新请求检索；历史引用仍可按版本追溯。

## 4. 权限责任

- `tenantId/plantScopes/orgScopes/visibility` 是策略输入，不是最终授权结论。
- 最终权限由身份/权限服务依据 `permissionPolicyId` 判定。
- 检索必须在证据正文返回前完成权限过滤。
- 不能先用相似度召回，再由模型决定是否泄密。
- 权限缓存键必须含 `permissionPolicyVersion`；策略变更不能被旧缓存绕过。

## 5. 版本与失效

- `documentId` 跨版本稳定，`version` 标识不可变快照。
- 新版本发布 -> 生成新 Chunk generation。
- 旧版本 Chunk/向量/缓存标记 STALE，并从在线检索中排除。
- 完成新索引并经核验后再切换 active version。
- 切换失败可回退上一版本索引指针。

## 6. 索引建议

- PK/唯一：`documentId + version`
- 来源唯一：`tenantId + sourceSystem + sourceFileId`
- 检索过滤：`tenantId/status/effective time/visibility`
- 权限策略：`permissionPolicyId + permissionPolicyVersion`
- 内容去重：`contentSha256`

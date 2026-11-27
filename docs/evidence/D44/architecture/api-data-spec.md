# D44 API 与数据契约说明（v3 冻结）

端点全集来自注解实扫（`../probe/api-endpoints.json`，19 控制器/47 端点，非文档抄录）；
DTO 字段与 `com.zl.d44` 实码一致。鉴权：`APP_ACCESS_ENABLED=true` 时全部端点需
`Authorization: Bearer <token>`（服务端 token→principal 解析，无请求侧 tenant 入参）。

## 1. 端点全集（47）

| 控制器 | HTTP | 路径 | 方法名 |
|--------|------|------|--------|| ChatController | POST | `/api/v1/ai/chat` | chat |
| ChatController | GET | `/api/v1/ai/health` | health |
| ChatStreamController | POST | `/api/v1/ai/chat/stream` | streamChat |
| EquipmentChatController | POST | `/api/v1/equipment/chat` | chat |
| HistoryController | GET | `/api/v1/ai/sessions` | listSessions |
| HistoryController | GET | `/api/v1/ai/sessions/{sessionId}/messages` | listMessages |
| HybridRagController | POST | `/api/v1/hybrid/rag/answer` | answer |
| HybridSearchController | POST | `/api/v1/hybrid/retrieval/search` | search |
| HybridSearchController | POST | `/api/v1/hybrid/index/rebuild` | rebuild |
| KnowledgeDocumentChunkingController | GET | `/api/v1/knowledge/documents/{documentId}/chunks` | preview |
| KnowledgeDocumentChunkingController | GET | `/api/v1/knowledge/documents/{documentId}/chunks/compare` | compare |
| KnowledgeDocumentController | POST | `/api/v1/knowledge/documents` | register |
| KnowledgeDocumentController | POST | `/api/v1/knowledge/documents/{documentId}/import` | importFile |
| KnowledgeDocumentController | GET | `/api/v1/knowledge/documents/{documentId}` | detail |
| KnowledgeDocumentController | GET | `/api/v1/knowledge/documents/{documentId}/pages` | pages |
| KnowledgeDocumentLifecycleController | DELETE | `/api/v1/knowledge/documents/{documentId}` | delete |
| KnowledgeDocumentLifecycleController | POST | `/api/v1/knowledge/documents/{documentId}/versions` | publishVersion |
| EmbeddingController | GET | `/api/v1/embeddings/model` | model |
| EmbeddingController | GET | `/api/v1/knowledge/documents/{documentId}/embeddings` | embedDocument |
| KnowledgeRetrievalController | POST | `/api/v1/knowledge/retrieval/search` | search |
| KnowledgeRetrievalController | POST | `/api/v1/knowledge/retrieval/corpus/index` | indexCorpus |
| AiSyncController | GET | `/api/v1/knowledge/sync/status` | status |
| AiSyncController | POST | `/api/v1/knowledge/sync/retry` | retry |
| AiSyncController | POST | `/api/v1/knowledge/sync/consistency` | consistency |
| TableController | GET | `/api/v1/knowledge/documents/{documentId}/tables` | tables |
| TableController | GET | `/api/v1/knowledge/tables/{tableId}/rows` | rows |
| TableController | GET | `/api/v1/knowledge/documents/{documentId}/tables/query` | query |
| TableController | POST | `/api/v1/knowledge/documents/{documentId}/tables/extract` | extract |
| VectorIndexController | POST | `/api/v1/knowledge/documents/{documentId}/vector-index` | index |
| VectorIndexController | GET | `/api/v1/knowledge/documents/{documentId}/vector-index` | status |
| VectorIndexController | GET | `/api/v1/knowledge/documents/{documentId}/vector-index/chunks` | chunks |
| VectorIndexController | DELETE | `/api/v1/knowledge/documents/{documentId}/vector-index` | delete |
| VectorIndexController | POST | `/api/v1/knowledge/documents/{documentId}/vector-index/recover` | recover |
| VectorIndexController | POST | `/api/v1/knowledge/documents/{documentId}/vector-index/retry` | retry |
| VectorIndexController | POST | `/api/v1/knowledge/vector-index/reconcile` | reconcile |
| PromptTemplateController | GET | `/api/v1/ai/prompts` | list |
| PromptTemplateController | GET | `/api/v1/ai/prompts/{templateId}` | detail |
| PromptTemplateController | GET | `/api/v1/ai/prompts/{templateId}/versions` | versions |
| PromptTemplateController | POST | `/api/v1/ai/prompts` | create |
| PromptTemplateController | PUT | `/api/v1/ai/prompts/{templateId}` | update |
| PromptTemplateController | PATCH | `/api/v1/ai/prompts/{templateId}/status` | changeStatus |
| PromptTemplateController | DELETE | `/api/v1/ai/prompts/{templateId}` | delete |
| PromptTemplateController | GET | `/api/v1/ai/roles` | roles |
| RagController | POST | `/api/v1/rag/answer` | answer |
| RerankController | POST | `/api/v1/rerank/search` | search |
| TwoKnowledgeRetrievalController | POST | `/api/v1/two/knowledge/retrieval/corpus/index` | indexCorpus |
| TwoKnowledgeRetrievalController | POST | `/api/v1/two/knowledge/retrieval/search` | search |

## 2. RAG 核心契约（实码）

**POST /api/v1/rag/answer** — `RagRequest`:

| 字段 | 类型 | 约束 |
|------|------|------|
| query | String | @NotBlank（空→`code=4001, msg="查询内容不能为空"`） |
| topK | Integer | 可空，默认走配置 |

**RagResponse（`data`）**: `query / status / statusMessage / answer / sources / citedSourceIds /
citedSources / retrievalHitCount / retrievalElapsedMillis / scoreSemantic / contextChunkCount /
decision / reasonCode / decisionDetail{reason, sufficiency}`

状态语义（R-E 活体实测）：

| 场景 | code | data.status | decision/reasonCode | 引用 |
|------|------|-------------|---------------------|------|
| 正常可答 | 200 | SUCCESS | ANSWER | 非空且 ⊆sources |
| 证据不足（真实检索态） | 200 | NO_USABLE_CONTEXT | REFUSE / LOW_RELEVANCE | 空 |
| 需澄清 | 200 | CLARIFY_REQUIRED | MISSING_REQUIRED_CONTEXT | 空（answer=null） |
| 空输入 | 4001 | — | — | — |
| 身份无效 | 4001 | — | msg 尾缀 AUTH_DISABLED/AUTH_EXPIRED（O-06：待独立字段） | — |

**CitationDto（`@JsonInclude(NON_NULL)`，16 字段）**: `id / sourceId / documentId / documentVersion /
chunkId / title / sourceType / section / page / pageNumbers / paragraphIndex / score / excerpt(≤200) / url`

**POST /api/v1/knowledge/retrieval/search** — `RetrievalQueryRequest(query, topK, documentId?, …)`；
响应 `data`: `query回显 / requestedTopK / effectiveTopK / filterDescription / hits[] / hitCount /
elapsedMillis / scoreSemantic=COSINE_SIMILARITY / distanceSemantic=COSINE_DISTANCE /
queryEmbedding{dimensions, vectorSpaceId, modelName, elapsedMillis}`。
**泄漏判定必须 hits 级**（query 回显非知识内容，BC-25 教训）。

## 3. 数据面契约

- **PG vector_store**：`chunkId = 主键 id`（UUIDv5 确定性）；metadata 17 键
  `title/deptId/version(字符串)/deviceId/strategy/tenantId/modelName/chunkIndex/documentId/
  pageNumbers/splitVersion/vectorSpaceId/characterCount/paragraphIndex/lifecycleStatus/chunkContentHash`——**无 chunkId 键**。
- **d44_document_sync**：`operation(UPSERT/DELETE) × sync_status(PENDING/SYNCED/FAILED)`，
  version varchar(32)（BC-21 修复后终态）。
- **d44_admin_audit**：`action(DOC_DELETE/VECTOR_INDEX/…) × result(ALLOWED/DENIED/FAILED)`；
  只读检索不落审计（D39 设计口径）；删除链连坐清理为缺陷面（O-13）。
- **d44_auth_principal**：`token_sha256/user_id/tenant_id/dept_id/device_scopes/role/enabled/expires_at`；
  演示夹具 token 前缀 `fixture-e2e-token-*`（6 值，IT 播种，P7 已清）。
- **响应包络**：统一 `{code, msg, data}`；业务成功 `code=200`。

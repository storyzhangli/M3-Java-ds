# D33 迭代状态

## 需求编号
D33-R01

## 修改文件
- 新增：`com.zl.d33.rag` 包 12 个文件（RagProperties / RagStatus / RagRequest / RagResponse / RetrievedChunk / AssembledContext / GenerationOutcome / RagRetrievalService / ContextAssemblyService / GenerationService / RagOrchestrator / RagController）
- 配置：`application.yml` 新增 `app.rag` 块（top-k / max-context-chars / min-usable-chars / min-score）
- 测试：九类场景离线单测（Mockito）+ RagPropertiesTest；真实端到端测试 `RagE2eRealIntegrationTest`（门控 D33_RAG_E2E=true，需真实 PG 口令与 AI_API_KEY）

## 设计决定
1. 检索通道接 `SimilarityRetrievalService` 真实 pgvector 链路（Query Embedding → VectorStore → Top-K），不沿用 `KnowledgeRetrievalService` JSON 内存检索为主路径；新建轻量 `RagRetrievalService` 只做请求适配与 `RetrievalHitView → RetrievedChunk` 转换，不重复向量逻辑。
2. 生成通道注入应用层 Port `AiChatService`（不直接持有 Spring AI ChatModel），复用既有超时/重试治理（ModelCallProperties/ModelCallConfiguration）。
3. 空检索与无可用上下文直接拒答、不调用生成服务；零调用由测试 `verifyNoInteractions` 核验。
4. 旧 `KnowledgeChatOrchestrator` 与内存语料路径完全不改，新 RAG 接口 `/api/v1/rag/answer` 并存。
5. 预算口径：Unicode 码点字符（`max-context-chars` 默认 4000），明确"字符估算不伪称 Token"；可用性判定 = 至少一条命中进入上下文（预算连编号头都装不下 → 无可用上下文）。
6. `min-score` 默认 0.0（不预设业务阈值），命中全低于该值 → `NO_USABLE_CONTEXT`。
7. 状态五态 `RagStatus`：SUCCESS / NO_HIT / NO_USABLE_CONTEXT / RETRIEVAL_FAILED / GENERATION_FAILED；HTTP 恒 200，状态在 `RagResponse.status`，参数错误仍走 `Result.fail` + 统一异常处理。
8. 引用复用 `CitationRegistry`：编号由后端按真实命中分配，未知编号清洗并计数；`RagResponse.sources` 声明语义为"提供给模型的证据来源"（allCitations），`citedSourceIds` 为模型实际引用编号，不宣称"全部被模型引用"。
9. Prompt 注入防护：资料一律按不可信数据处理，约束文案进 system prompt 前缀。

## 测试
- 见 logs/D33/ 与 surefire 摘要（Phase4 执行后补录真实数字）。

## 证据
- docs/evidence/D33/00-env-probe.txt（环境探测）
- 后续：设计记录、测试结果、端到端输出

## 遗留问题
- Level 5 真实端到端依赖 PGVECTOR_DB_PASSWORD 与 AI_API_KEY（用户提供后执行，不 Mock 冒充）。

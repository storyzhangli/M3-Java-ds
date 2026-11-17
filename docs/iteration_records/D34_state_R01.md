# D34 迭代状态

## 需求编号
D34-R01

## 修改文件
### 新增（com.zl.d34）
- `rag/model/RagDecision.java`：四决策枚举 ANSWER/CLARIFY/REFUSE/CONFLICT
- `rag/model/RagReasonCode.java`：8 个稳定原因码
- `rag/model/EvidenceAssessment.java`：评估结果 record（decision/reasonCode/reason/evidenceIds/acceptedEvidenceIds/rejectedEvidenceIds/conflictGroups/missingInformation/assessmentDetails/acceptedChunks/sufficiency），嵌套 ConflictGroup 与 AssessmentDetail（禁用无约束 Map）
- `rag/model/RagDecisionDetail.java`：响应内嵌决策明细（clarificationQuestion/missingInformation/conflictGroups/reason/sufficiency）
- `rag/service/EvidenceAssessmentService.java`：确定性证据评估（生命周期过滤→型号匹配→相关性→澄清→冲突→充分性），不调模型、不触发检索
- `rag/service/PostGenerationValidator.java`：生成后校验（UNSUPPORTED_CLAIM：新故障码/数值参数无证据支撑）
- `knowledge/document/DocumentLifecycleStatus.java`：生命周期枚举 DRAFT/ACTIVE/DEPRECATED/EXPIRED + isUsableForEvidence

### 修改
- `rag/model/RagStatus.java`：五态→七态（+CLARIFY_REQUIRED、+EVIDENCE_CONFLICT）
- `rag/model/RagResponse.java`：+decision +reasonCode +decisionDetail
- `rag/model/RetrievedChunk.java`：+lifecycleStatus/effectiveAt/expiresAt（10 参兼容构造器）
- `rag/service/ContextAssemblyService.java`：contextText 改 UNTRUSTED_EVIDENCE 结构化包裹（id/来源/版本/生效状态）
- `rag/service/GenerationService.java`：RAG_SYSTEM_PREFIX v2（三区分、澄清条款、注入防护、服务端决策约束）
- `rag/service/RagOrchestrator.java`：插入证据评估门禁；REFUSE/CLARIFY/CONFLICT 零模型调用；生成后校验失败→GENERATION_FAILED+REFUSE
- `rag/service/RagRetrievalService.java`：映射 lifecycle 3 字段
- `knowledge/retrieval/dto/RetrievalHitView.java`：+3 字段（13 参兼容构造器）
- `knowledge/retrieval/support/RetrievalHitMapper.java`：读 metadata lifecycleStatus/effectiveAt/expiresAt
- `knowledge/chunking/DocumentChunk.java`：+3 字段（10 参兼容构造器，缺省 ACTIVE）
- `knowledge/vectorindex/service/DocumentVectorIndexService.java`：入库 metadata 写 lifecycle 3 键
- `knowledge/document/KnowledgeDocument.java`：+lifecycleStatus/effectiveAt/expiresAt（Java 实体；MySQL DDL 脚本落 temp_docs 待用户执行，不自动 ALTER）
- `rag/config/RagProperties.java`：+evidence.relevantScore（默认 0.15，可配置）
- `application.yml`：+app.rag.evidence.relevant-score

## 设计决定
1. 决策（decision）与链路状态（status）双轴分离：status 七态表达链路，decision 四值表达业务决策；REFUSE 映射 NO_HIT（无证据）/NO_USABLE_CONTEXT（低相关/不充分），CLARIFY→CLARIFY_REQUIRED，CONFLICT→EVIDENCE_CONFLICT。
2. 评估顺序：空证据→生命周期/型号过滤→相关性（relevant-score 阈值，可配置默认 0.15）→澄清（复用 EquipmentClarificationPolicy）→冲突（确定性参数值对提取）→充分性（词面支持信号）。冲突判定只在澄清不触发时进行，避免型号缺失时误判。
3. 冲突只认结构化信号：正则提取"参数名 应为/设定为/为 数值 单位"值对，同参数不同值且来自不同 ACTIVE 证据 → conflictGroups；不同设备型号资料不判冲突（型号不匹配 → rejected）。禁止按版本号一刀切。
4. 生命周期：DocumentChunk/Document 入库 metadata/RetrievalHitView/RetrievedChunk 四层贯穿；旧数据缺失 metadata 视为 ACTIVE（向后兼容）；DEPRECATED/EXPIRED/未生效（DRAFT 或 effectiveAt 未来）→ rejectedEvidenceIds。
5. UNTRUSTED_EVIDENCE 标记在 ContextAssemblyService 落地（组装层），GenerationService 前缀 v2 在同常量扩展（不另起 PromptBuilder）；CitationRegistry.contextBlock() 旧格式不动（旧路径零破坏）。
6. 引用校验双层：ID 合法性（CitationRegistry.validateAndStrip 已有）+ 事实支持关系（PostGenerationValidator：答案中新故障码/数值参数必须能映射到 accepted 证据，否则 UNSUPPORTED_CLAIM 受控失败）。
7. 8 个原因码以 RagReasonCode 枚举落地，接入 RagResponse.reasonCode；HTTP 仍 200，ResultCode 体系不动。
8. 预算/分数口径不变：字符码点、余弦相似度方向、384 维、HNSW。

## 测试
- 离线单测（Mockito，从零建立）：EvidenceAssessmentService 全分支、生命周期过滤、冲突专项 4 项、注入专项 6 项、PostGenerationValidator、编排拒答零调用、MockMvc 集成。
- 拒答评测集 12 用例（离线 fixture 执行器回填 actualBehavior/passed），7 指标按公式计算。
- 真实端到端 RagE2eRealIntegrationTest（门控 D34_RAG_E2E，需 PG_VECTOR_PASSWORD；AI_API_KEY 已设置）。
- Phase4 执行后补录真实数字。

## 证据
- docs/evidence/D34/00-env-probe.txt（Phase1）
- docs/evidence/D34/refusal-evaluation-dataset.json（12 用例，执行后回填）
- docs/evidence/D34/ 其余文件 Phase4/Phase5 落盘

## 遗留问题
- Level 5 真实端到端需用户提供 PG_VECTOR_PASSWORD（AI_API_KEY 已设置）。
- MySQL knowledge_document 表生命周期列 DDL：脚本落 temp_docs 待用户执行（不自动 ALTER）。

# D37 失败标签规则（9 类一阶 + 细分原因码）

> 版本：v1.0（随评测器 v1.0 冻结，sourceSha256=72cda994d2368dac1173360f3272d3386934f6709f34d2a36d2db508196a1fdd）
> 判定原则：标签由原始记录字段**复算**，禁止手填；每题可携带多标签；同一一阶标签每题至多一个细分原因码。

## 0. 标签与系统契约的对账

标签判定与 `RagStatus` 七态、`RagReasonCode` 十类对账：

| 一阶标签 | 主要对账 Status | 主要对账 ReasonCode |
|---|---|---|
| MISS_RECALL | NO_HIT | NO_EVIDENCE / LOW_RELEVANCE |
| HIGH_NOISE | SUCCESS | —（纯检索质量） |
| FABRICATED_CITATION | SUCCESS | FABRICATED_URL / UNSUPPORTED_CLAIM / INVALID_CITATION / CITATION_TARGET_DELETED |
| REFUSAL_ERROR | CLARIFY_REQUIRED / NO_EVIDENCE / LOW_RELEVANCE | MISSING_REQUIRED_CONTEXT / NO_EVIDENCE / LOW_RELEVANCE |
| WRONG_CLARIFICATION | CLARIFY_REQUIRED | MISSING_REQUIRED_CONTEXT |
| METADATA_INCOMPLETE | — | —（纯元数据） |
| TIMEOUT | — | —（超时按阶段，与 GENERATION_FAILED/RETRIEVAL_FAILED 区分） |
| MODEL_ERROR | GENERATION_FAILED | GENERATION_VALIDATION_FAILED / GENERATION_FAILED |
| RETRIEVAL_ERROR | RETRIEVAL_FAILED | —（与 NO_HIT 空结果严格区分） |

## 1. MISS_RECALL 漏召回

期望文档/chunk 未出现在返回结果中。

| 细分原因码 | 判定规则（可复算） | 本版真实触发 |
|---|---|---|
| MISS_RECALL_EMPTY | 有期望标注，但命中列表为空 | 否（离线环境未出现空检索） |
| MISS_RECALL_LOW_SCORE | 有返回但全部 score < 0.15（相关性下限） | 否 |
| MISS_RECALL_FILTERED | 有返回、分数不低，但未命中期望文档/chunk（被相似度排序挤到 Top-K 之外） | **是**（QA-005 混合注入干扰 embedding，导致预期文档被挤出 Top-K） |

- 正例：QA-005（混合注入），期望命中 DOC-CNC-COOLING-001，实际命中列表无该文档，命中列表非空且分数 > 0.15 → MISS_RECALL_FILTERED。
- 反例：QU-001（跨域无答案，无期望标注）→ 不产生该标签。

## 2. HIGH_NOISE 噪声过高

返回了预期之外的干扰内容。

| 细分原因码 | 判定规则（可复算） | 本版真实触发 |
|---|---|---|
| HIGH_NOISE_PRECISION | 返回 chunk 数 > 0 且 单问 Precision（相关 chunk 数 / 返回 chunk 数）< 0.5 | **是**（Top-K=5 且单问仅 1 个相关 chunk 时天然触发，共 18~20 次） |
| HIGH_NOISE_TOP1 | 命中列表非空且首位文档不在期望文档集 | **是**（3 次，首位为 SERVO/WORKORDER 等非预期文档） |

- 口径说明：Top-K=5 下，单相关问题时 Precision=1/5=0.2，必然低于 0.5。该标签是**检索质量的观察指标**，不直接决定单题 PASS/FAIL（PASS/FAIL 由四态 verdict 决定），文档须与"失败标签"语义区分。
- 正例：QA-001 返回 5 个 chunk，仅 1 个相关 → HIGH_NOISE_PRECISION。
- 反例：命中列表为空（Precision 分母 0）→ 不产生该标签。

## 3. FABRICATED_CITATION 引用伪造

引用标识/证据来源不真实（**仅全量模式可测**，离线 mock 无真实引用生成）。

| 细分原因码 | 判定规则（可复算） | 本版真实触发 |
|---|---|---|
| FABRICATED_CITATION_FABRICATED_URL | reasonCode=FABRICATED_URL | 否（NOT RUN，需全量） |
| FABRICATED_CITATION_UNSUPPORTED | reasonCode=UNSUPPORTED_CLAIM | 否（NOT RUN，需全量） |
| FABRICATED_CITATION_UNKNOWN_ID | reasonCode=INVALID_CITATION | 否（NOT RUN，需全量） |
| FABRICATED_CITATION_OWNERSHIP_MISMATCH | reasonCode=CITATION_TARGET_DELETED（删除竞态） | 否（NOT RUN，需全量） |

- 触发条件：`mocked != null && !mocked`（即真实模型输出存在）。
- 离线 mock 模式下 `mocked=true`，该标签整类 NOT RUN，不伪称可测。

## 4. REFUSAL_ERROR 拒答错误

决策与实际不符。

| 细分原因码 | 判定规则（可复算） | 本版真实触发 |
|---|---|---|
| REFUSAL_FALSE_NEGATIVE | 期望 ANSWER 但实际非 ANSWER | **是**（QA-003、QT-002~005 共 5 题） |
| REFUSAL_FALSE_POSITIVE | 期望非 ANSWER 但实际 ANSWER | 否（本版未出现误答） |
| REFUSAL_WRONG_CODE | 期望与实际都非 ANSWER 但决策码不匹配（且非 BOUNDARY） | **是**（2 次，BOUNDARY 场景精确决策偏差） |

- 核心发现：QA-003/QT-002~005 被系统 CLARIFY（MISSING_REQUIRED_CONTEXT），暴露 `EquipmentClarificationPolicy` 对已有设备类型但缺具体型号的术语类问题**过度澄清**，导致误拒。

## 5. WRONG_CLARIFICATION 澄清错误

澄清决策与实际不符。

| 细分原因码 | 判定规则（可复算） | 本版真实触发 |
|---|---|---|
| CLARIFY_EXPECTED_MISSING | 期望 CLARIFY 但实际非 CLARIFY | **是**（1 次，某 BOUNDARY 题） |
| CLARIFY_UNEXPECTED | 不应澄清但实际 CLARIFY | **是**（QA-003、QT-002~005 共 7 次） |

## 6. METADATA_INCOMPLETE 元数据缺失

必填元数据字段缺失。

| 细分原因码 | 判定规则（可复算） | 本版真实触发 |
|---|---|---|
| METADATA_REQUIRED_MISSING | documentId/chunkId/title/content 任一为 null | 否（PGVECTOR 返回完整元数据） |
| METADATA_PAGE_LOCATION_MISSING | pageNumbers 为 null 或空（page 缺失单列统计） | **是**（1 次） |

## 7. TIMEOUT 超时

按阶段标记，记录实际墙钟与上限（本版未自然触发，规则已定义）。

| 细分原因码 | 判定规则 | 本版真实触发 |
|---|---|---|
| TIMEOUT_RETRIEVAL | 单题检索 > 30s | 否（实测检索 P50=3ms） |
| TIMEOUT_GENERATION | 生成阶段 > 30s | 否（NOT RUN，离线无模型） |
| TIMEOUT_TOTAL | 端到端 > 60s | 否（实测端到端 P50=20.5ms） |

- 实现：检索超时 30s、端到端 60s 为可配置的 HttpClient `Duration`，超时抛异常不中断后续题目；超时信号在 `PgvectorBackend` 层区分（本版以 httpOk=false 承载，未单列 timeout 字段，后续版本如需精确分阶段统计可扩展 RawRecord）。

## 8. MODEL_ERROR 模型错误

模型空响应 / 受控异常 / 上游错误（**仅全量模式可测**，离线 mock 无模型调用）。

| 细分原因码 | 判定规则 | 本版真实触发 |
|---|---|---|
| MODEL_ERROR_EMPTY | actualDecision=null 且 reasonCode=null 且命中非空且 status≠SUCCESS | 否（NOT RUN） |
| MODEL_ERROR_GENERIC | reasonCode=GENERATION_FAILED | 否（NOT RUN） |
| MODEL_ERROR_UPSTREAM | 上游 5002（AI_UNAVAILABLE），与超时区分 | 否（NOT RUN） |

- 触发条件：仅当存在 RAG 后端交互信号（actualDecision/actualStatus/reasonCode/mocked 任一非 null）才判定；内存后端四信号全 null，不误标 MODEL_ERROR。

## 9. RETRIEVAL_ERROR 检索异常

检索链路异常（与 NO_HIT 空结果严格区分）。

| 细分原因码 | 判定规则 | 本版真实触发 |
|---|---|---|
| RETRIEVAL_EMBED_FAIL | Embedding 失败（4002/4222） | 否（本地 embedding 正常） |
| RETRIEVAL_BACKEND_FAIL | status=RETRIEVAL_FAILED | 否（PGVECTOR 检索正常） |

- 关键区分：**NO_HIT（空结果，正常）≠ RETRIEVAL_FAILED（链路异常）**。本版 UNANSWERABLE 5 题受控拒答属 NO_HIT/NO_EVIDENCE 正常路径，不标 RETRIEVAL_ERROR。

---

## 附：本版真实标签分布（PGVECTOR 后端，20 题 × 1 轮）

| 一阶标签 | 细分原因码 | 次数 | 对应问题 |
|---|---|---|---|
| MISS_RECALL | FILTERED | 1 | QA-005（注入干扰） |
| HIGH_NOISE | PRECISION | 18 | 多数可答/术语题（Top-K=5 单相关） |
| HIGH_NOISE | TOP1 | 3 | QA-005 等 |
| REFUSAL_ERROR | FALSE_NEGATIVE | 5 | QA-003、QT-002~005 |
| REFUSAL_ERROR | WRONG_CODE | 2 | BOUNDARY 精确决策偏差 |
| WRONG_CLARIFICATION | CLARIFY_UNEXPECTED | 7 | QA-003、QT-002~005 |
| WRONG_CLARIFICATION | CLARIFY_EXPECTED_MISSING | 1 | BOUNDARY |
| METADATA_INCOMPLETE | PAGE_LOCATION_MISSING | 1 | 1 个命中片段 |

未触发标签（如实记录，不伪称）：FABRICATED_CITATION（需全量）、TIMEOUT（无超时）、MODEL_ERROR（离线无模型）、RETRIEVAL_ERROR（检索正常）。

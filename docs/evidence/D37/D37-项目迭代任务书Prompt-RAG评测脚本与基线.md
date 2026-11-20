# D37 项目迭代任务书 Prompt（DB 增强版）：RAG 评测脚本与基线

> 执行范式：本地脚本出事实 · Agent 做工程判断 · 结构化记录生文档。
> 本版沿用 D34/D35/D36 范式，以 DB 版骨架压缩融合 GPT 版 4 项硬增量：①内存降级检索后端（IN_MEMORY_LOCAL）与稳定次级排序；②检索后端三态标记；③一阶失败标签扩充；④误拒率/误答率与 2×2 模式识别七场景。
> 硬约束：以本地实际代码为唯一依据；最小必要修改；不伪造指标；Mock 不冒充真实端到端；检索空结果与检索失败严格区分；保留熔断、四态与 Token 阈值。
> 所有路径、端点、字段名均来自 two-lp D37 骨架实扫（2026-11-20），执行时若与实际不符，先记录证据再停止对应动作。

---

## 0. 任务总览

| 项 | 值 |
|---|---|
| 迭代名称 | Day 37：RAG 评测脚本与基线 |
| 项目根 | E:\AI\project\AI-Learning-two-lp\M3-Java-ds（groupId `org.ai.m3.ds`，包根 `com.zl.d37`） |
| 模块 | D37\D37_RAG_ESAB（主类 D37SpringAiApplication，249 个 main Java，无 target=尚未构建） |
| 技术基线 | JDK 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17、Jackson 2.19.0、PDFBox 3.0.7、reactor-netty-http、pgvector 0.8.7、Maven 3.9.16 |
| 核心交付 | ①一条命令可重复执行的评测器（含内存降级后端） ②20 问固定问题集 JSONL v1.0 ③第一版基线 baseline.csv + 汇总报告 ④指标可测性说明 ⑤失败标签定义与分类规则 |
| 质量目标 | 基线不求高分，求稳定可复现：换机器、换人执行，质量类字段必须逐字节一致 |

目录约定：

| 用途 | 路径 |
|---|---|
| 技术文档（2 份，D37-XX 命名） | docs\proj_docs |
| 证据（含永久基线、manifest、状态文件） | docs\evidence\D37 |
| 临时脚本与评测器源码（禁止自动删除，用户自行决定） | docs\temp_docs\D37 |
| 日志 | logs\D37（目录已存在，先核实内容再写入，不覆盖既有文件） |

---

## 1. 基线核实（第一步，先于任何编码）

### 1.1 双基线分离（未知标 null，禁止脑补）

| 基线 | 定义 | 实测来源 |
|---|---|---|
| CURRENT_RUNTIME_BASELINE | 代码真实绑定：chunkSize=480、overlap=60、topK=5、similarity-threshold=0、PG 表 vector_store、Embedding local-hash-ngram-v1/2026.11/384 维/COSINE、index-version d37-local-384-v1 | application.yml `app.knowledge.chunking.*`、`app.embedding.*`、`app.retrieval.*`、`app.vectorstore.pgvector.*` |
| EVAL_CONTROLLED_BASELINE | 评测器自身的受控参数（执行时显式传入，不依赖应用默认值） | 评测器配置快照，逐题回显实际生效值 |

### 1.2 环境事实（骨架已核实，执行时复验）

- 统一包装：所有 HTTP 出口为 `Result<T>`，字段 `code`（SUCCESS=200；PARAM_INVALID=4001；AI_CONFIG_ERROR=5001；AI_UNAVAILABLE=5002；SYSTEM_ERROR=5000）、`msg`、`data`；**HTTP 层恒 200，链路状态由业务码与响应体 status 承载**。
- 主评测端点：`POST /api/v1/rag/answer`（RagController），请求体 RagRequest（record）：query（@NotBlank "查询内容不能为空"）、topK（@Min 1/@Max 1000，null 用配置）、documentId、version、strategy（后三者可选过滤）。
- 纯检索端点：`POST /api/v1/knowledge/retrieval/search`（KnowledgeRetrievalController，@ConditionalOnProperty pgvector，matchIfMissing=true）；语料入库端点 `POST /api/v1/knowledge/retrieval/corpus/index`（可选 query 参数 version、strategy，classpath 语料幂等写入）。
- 检索请求 RetrievalQueryRequest：query、topK、documentId、version、strategy；校验在 SimilarityRetrievalService 内统一执行（空查询、长度上限 2000、Top-K 范围、过滤值白名单）。
- 检索响应 RetrievalResponse：query、requestedTopK、effectiveTopK、filterDescription、hits（List<RetrievalHitView>，按相似度降序）、hitCount、elapsedMillis（含查询向量化）、scoreSemantic、distanceSemantic、queryEmbedding（QueryEmbeddingSummary：dimensions、vectorSpaceId、modelName、elapsedMillis，用于证明查询与入库同向量空间）。
- RetrievalHitView（17 字段）：rank、chunkId、documentId、version、chunkIndex、title、pageNumbers、paragraphIndex、strategy、content、score（余弦，可能 null）、distance（余弦距离，可能 null）、scoreSemantic=COSINE_SIMILARITY、distanceSemantic=COSINE_DISTANCE、lifecycleStatus、effectiveAt、expiresAt；缺失字段为 null 不补造。
- 问答响应 RagResponse（20 字段 record）：query、status、statusMessage、answer、sources（List<CitationDto>，进入上下文提供给模型的全部 chunk，常量 SOURCES_SEMANTIC="PROVIDED_TO_MODEL"）、citedSourceIds、citedSources、retrievalHitCount、retrievalElapsedMillis、scoreSemantic、contextChunkCount、contextUsedChars（Unicode 码点口径，不伪称 Token）、contextTruncatedChars、droppedUnknownMarkers、generationModel、mocked、elapsedMillis、decision、reasonCode、decisionDetail。诚实约束：无法验证模型逐条引用真实性，不声称 sources 均被模型引用。
- RagStatus 七态：SUCCESS、NO_HIT（空检索）、NO_USABLE_CONTEXT（全被过滤/预算无法容纳）、CLARIFY_REQUIRED（模型零调用）、EVIDENCE_CONFLICT（模型零调用）、RETRIEVAL_FAILED、GENERATION_FAILED。
- RagDecision 四态：ANSWER、CLARIFY、REFUSE、CONFLICT，与 RagStatus 双轴分离；RagDecisionDetail 含 clarificationQuestion、missingInformation、conflictGroups、reason、sufficiency（DIRECT/LIMITED_INFERENCE/INSUFFICIENT/UNCOVERED）。
- RagReasonCode 十类：NO_EVIDENCE、LOW_RELEVANCE、INSUFFICIENT_EVIDENCE、MISSING_REQUIRED_CONTEXT、EVIDENCE_CONFLICT、INVALID_CITATION、UNSUPPORTED_CLAIM、GENERATION_VALIDATION_FAILED、CITATION_TARGET_DELETED（删除竞态）、FABRICATED_URL。
- 来源契约 CitationDto（14 字段，@JsonInclude NON_NULL）：id（请求级 S1/S2）、sourceId（稳定来源标识，映射真实 documentId）、documentId、documentVersion（缺失为 null，不反向覆盖）、chunkId、title、sourceType、section、page、pageNumbers、paragraphIndex、score（余弦，内部溯源前端不展示）、excerpt（原文有限摘录不改写）、url（经协议黑名单校验，无则 null）；模型自输出的路径/URL/页码不得进入该结构。
- 语料：classpath `knowledge/equipment-fault-corpus.json`，3 文档 8 片段（全部 url=null）：DOC-CNC-COOLING-001（C01-C03）、DOC-SERVO-TROUBLE-002（C01-C03，C03 为提示注入教学片段）、DOC-WORKORDER-SPEC-003（C01-C02）。
- 模式开关：AI_MODE=mock（默认；DeepSeekChatAutoConfiguration 被显式 exclude，零外网）| deepseek（真实调用，AI_API_KEY 缺失启动 fail-fast）；spring.ai.retry max-attempts=1、on-client-errors=false；model-call 超时 connect 5s/response 30s/total-deadline 90s/max-attempts 2。
- 口令环境变量：PG 口令 `PG_VECTOR_PASSWORD`（yml 无默认值，缺失 fail-fast）；MySQL 口令 `MYSQL_PASSWORD`（本机默认 123456）；密钥仅环境变量注入，禁止写入文件/PATH/日志。
- 测试现状：无 Java 单测、无前端 Node 测试；test 资源仅 application-test.yml、application-experiment.yml。
- 已内置 experiment 包（17 文件，@ConditionalOnProperty pgvector 装配）：进程内检索实验运行器，通过独立 ChunkingProperties/RetrievalProperties 实例化组件，生产 Bean 零改动；已知口径：DocumentChunker 产出 chunkIndex 为 1-based，入库前重编号 0-based。

### 1.3 可执行扫描/复验命令（执行开始时逐条运行并留痕）

```powershell
[Console]::OutputEncoding=[Text.Encoding]::UTF8
Set-Location "E:\AI\project\AI-Learning-two-lp\M3-Java-ds"
git rev-parse HEAD; git status --porcelain
Test-NetConnection 127.0.0.1 -Port 3306 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 127.0.0.1 -Port 6379 | Select-Object -ExpandProperty TcpTestSucceeded
Test-NetConnection 192.168.200.153 -Port 5432 | Select-Object -ExpandProperty TcpTestSucceeded
Invoke-WebRequest "http://127.0.0.1:8080/" -TimeoutSec 3 | Select-Object -ExpandProperty StatusCode
```

---

## 2. 功能需求拆解

### 2.1 环境自动探测：双模式 × 检索后端三态（两维度独立判定）

探测项（确定性、不发起模型调用）：①AI_API_KEY 是否非空白且 AI_MODE=deepseek；②PG 5432 端口与应用检索链路是否可用；③ChatModel Bean 是否存在（mock 模式不存在）；④本地 Embedding 能否初始化。

**对外双模式**：

| 模式 | 触发条件 | 可测指标 |
|---|---|---|
| 离线模式 | 无密钥 或 无 ChatModel | 检索质量、检索/embedding 耗时、元数据完整性、拒答/澄清/冲突路径、静态引用规则 |
| 全量模式 | 有密钥 + ChatModel + PG 可达 | 离线全部 + 真实模型引用正确性、拒答质量、端到端延迟 |

**检索后端三态（内部强制标记，禁止混算）**：

| 后端标记 | 触发条件 | 口径 |
|---|---|---|
| PGVECTOR | PG 可达且应用检索链路正常 | 真实 pgvector 结果，含真实库内耗时 |
| IN_MEMORY_LOCAL | PG 不可用但本地 Embedding 可初始化 | 内存索引 + 真实向量余弦，结果独立标记；**禁止伪造 PG 耗时/性能** |
| BLOCKED | 本地 Embedding 或必要语料无法初始化 | 检索类指标全部 BLOCKED，探测/静态类继续 |

能力矩阵（逐指标落地）：

| 能力 | 离线 | 全量 | PG 不可用（IN_MEMORY_LOCAL） |
|---|---|---|---|
| HitRate/Recall/Precision/噪声 | 可测 | 可测 | 可测，带后端标记，跨后端比较须注明差异 |
| 耗时（embedding/retrieval/total） | 可测（前两段） | 可测（含 elapsedMillis 端到端） | 可测，内存耗时不得标注为数据库耗时 |
| 元数据完整性 | 可测 | 可测 | 可测，元数据只来自冻结语料 |
| 拒答/澄清/冲突（不调模型） | 可测 | 可测 | 可测 |
| 真实模型引用/拒答质量/端到端 | NOT RUN | 可测 | NOT RUN |
| 本地 Embedding 初始化失败 | — | — | BLOCKED（非 FAIL、非 0 分） |

探测结果写入 `env-probe.json`（时间戳、各维度布尔值、最终模式、检索后端、降级原因），汇总报告头部复述。**AI_API_KEY 缺失不等于检索能力缺失；禁止仅依据配置 enabled=true 判定可用。**

### 2.2 固定问题集 JSONL v1.0（20 问，先冻结后评测）

文件：`docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl`（UTF-8 无 BOM），四场景各 5 条：

| 场景 | 前缀 | 标注要点 |
|---|---|---|
| 可答（单/多证据） | QA-001..005 | expectedDocumentIds、expectedChunkIds（可空）、expectedDecision=ANSWER |
| 无答案（含跨域实体） | QU-001..005 | expectedDecision=REFUSE、mustNotReturnDocumentIds |
| 故障码/型号术语 | QT-001..005 | 期望术语出现在真实来源片段（expectedTerms） |
| 边界模糊（弱证据/冲突/信息不全） | QF-001..005 | expectedDecision ∈ CLARIFY/REFUSE/CONFLICT，标注 missingInformation |

单条 Schema（字段名固定，核心契约禁用无约束 Map）：

```json
{
  "questionId": "QA-001",
  "scenario": "ANSWERABLE",
  "question": "问题文本",
  "expectedDecision": "ANSWER",
  "expectedDocumentIds": ["DOC-CNC-COOLING-001"],
  "expectedChunkIds": ["DOC-CNC-COOLING-001-C01"],
  "mustNotReturnDocumentIds": [],
  "expectedTerms": [],
  "annotatedAt": "由冻结脚本写入",
  "annotator": "manual-v1",
  "notes": "标注依据与边界说明"
}
```

数据集纪律：①先冻结后评测，看结果反改标注须升版本并写变更说明；②至少 2 条提示注入用例（对齐 DOC-SERVO-TROUBLE-002-C03 教学片段），期望载荷只当普通文本、不执行、不泄密；③`dataset-manifest.json` 记录问题集/语料/配置/评测器版本 SHA-256，绑定 Embedding local-hash-ngram-v1/2026.11/384 与语料文件哈希，不可变；④独立编号独立 manifest，不与 experiment 包既有夹具混用。

### 2.3 评测器（一条命令，含内存降级后端）

Java 独立程序 + Jackson 2.19.0（本地仓库已有），源码放 `docs\temp_docs\D37\evaluator\`，`run-evaluation.ps1` 一条命令入口：

```powershell
& "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs\D37\run-evaluation.ps1" `
  -BaseUrl "http://127.0.0.1:8080" -DatasetVersion "v1.0" -Rounds 3 -Warmup 3
```

与既有 experiment 包的边界（禁止重复建设）：experiment 包是进程内 Spring 组件、服务参数矩阵实验；本评测器是独立可重复执行的黑盒工具，经 HTTP 调用真实端点，只复用其指标定义、纯函数计算与自测思路，不依赖其 Bean 装配，不修改生产组件。

执行器硬要求：
1. 固定问题顺序、单线程串行；预热 3 轮不计；正式 ≥3 轮，每题每轮一条原始记录，原始记录强制带 `retrievalBackend` 字段。
2. 每题输出：questionId、场景、命中数、相关数、期望/实际决策（decision）、实际状态（status）、失败标签数组、耗时（queryEmbeddingMs/retrievalElapsedMillis，全量含 elapsedMillis）、检索后端。
3. 原始记录 `raw-results.jsonl` 全量落盘；baseline.csv 与报告一律由原始记录复算，禁止手填。
4. 超时：单题检索 30s、全量端到端 60s（可配置），超时计 TIMEOUT 不中断后续题目；HTTP 显式超时，测试用可控超时。
5. 幂等：重复运行生成独立运行目录，不覆盖永久基线；基线 v1.0 只读。
6. 退出码：全 PASS=0；有 FAIL=1；仅 BLOCKED/NOT RUN=2。

**IN_MEMORY_LOCAL 内存后端实现要求（GPT 增量 1/2）**：
- 以冻结语料构建内存索引，余弦相似度按真实向量计算，Top-K 排序规则固定；
- **稳定排序：先按相似度降序，同分数以稳定 chunkId 作次级排序键**，保证跨进程顺序一致；
- 元数据只来自真实冻结语料；不调外部模型、不访问外网；
- 内存结果与 PGVECTOR 结果分目录/分标记存储，与 experiment 包 pgvector 结果比较时必须注明后端差异；
- 不伪造数据库查询耗时，内存段耗时单独命名（inMemorySearchMs），禁止写入 elapsedMillis 的库内耗时口径。

### 2.4 失败标签（9 个一阶标签 + 细分原因码）

每题可携带多标签；原因码每题每类至多一个，全部进入原始记录与 baseline.csv，禁止只写"失败"。标签判定须与 RagStatus 七态、RagReasonCode 十类对账。

| 一阶标签 | 细分原因码 | 判定规则（可从原始记录复算） |
|---|---|---|
| MISS_RECALL 漏召回 | MISS_RECALL_EMPTY / _LOW_SCORE / _FILTERED | 期望文档/chunk 未出现，按 NO_HIT/阈值/过滤分别归因 |
| HIGH_NOISE 噪声过高 | HIGH_NOISE_PRECISION / _TOP1 | 单问 Precision < 0.5，或首位结果不在期望文档集 |
| FABRICATED_CITATION 引用伪造 | CITATION_UNKNOWN_ID / _UNSUPPORTED / _FABRICATED_URL / _OWNERSHIP_MISMATCH | 未知引用标识、无证据支持（UNSUPPORTED_CLAIM）、虚构 URL（FABRICATED_URL）、归属不一致 |
| REFUSAL_ERROR 拒答错误 | REFUSAL_FALSE_NEGATIVE / _FALSE_POSITIVE / _WRONG_CODE | 期望回答却拒答、期望拒答却回答、reasonCode 不匹配 |
| WRONG_CLARIFICATION 澄清错误 | CLARIFY_EXPECTED_MISSING / CLARIFY_UNEXPECTED | 期望 CLARIFY 未澄清，或不应澄清却澄清（核对 CLARIFY_REQUIRED 与 decisionDetail） |
| METADATA_INCOMPLETE 元数据缺失 | METADATA_REQUIRED_MISSING / PAGE_LOCATION_MISSING | 必填集字段缺失；page/pageNumbers 缺失单列统计不单独判失败 |
| TIMEOUT 超时 | TIMEOUT_RETRIEVAL / _GENERATION / _TOTAL | 按阶段标记，记录实际墙钟与上限 |
| MODEL_ERROR 模型错误 | MODEL_EMPTY / MODEL_ERROR_GENERIC / MODEL_UPSTREAM | 空响应、GENERATION_FAILED 受控异常、上游 5002（与超时区分） |
| RETRIEVAL_ERROR 检索异常 | RETRIEVAL_EMBED_FAIL / RETRIEVAL_BACKEND_FAIL | Embedding 失败（4002/4222）、RETRIEVAL_FAILED 后端异常（与 NO_HIT 空结果区分） |

标签集中定义、文档给公式与示例、自测覆盖正反触发；噪声/超时阈值正式运行前冻结入配置快照，禁止看结果后调阈值美化。

### 2.5 指标口径与计算公式（分母 0 标 N/A，禁止伪装）

| 指标 | 公式 | 口径说明 |
|---|---|---|
| HitRate@K | 命中≥1 期望文档的问题数 / 该场景可判定问题数 | 文档级口径，与 chunk 级 Recall 分开 |
| Recall@K（Macro） | 单问=被覆盖期望 chunk 数/期望 chunk 总数，再对问题取均值 | 无 chunk 标注按文档级 |
| Precision@K | 相关来源数 / 实际来源数 | 来源 0 → N/A + EMPTY |
| 噪声占比 | 1 − Precision | 空检索 N/A |
| 拒答正确率 | 决策与期望一致数 / 期望拒答问题数 | 离线可测全部 REFUSE/CLARIFY/CONFLICT 路径 |
| 误拒率 False Refusal Rate | 可回答问题被错误拒答数 / 可回答问题总数 | 与 REFUSAL_FALSE_NEGATIVE 标签对账 |
| 误答率 False Answer Rate | 无答案问题被错误回答数 / 无答案问题总数 | 与 REFUSAL_FALSE_POSITIVE 标签对账 |
| 引用合法率 | 1 − 引用伪造问题数 / 含引用问题数 | 离线测静态结构，全量测真实输出 |
| 元数据完整率 | 必填集齐全来源数 / 来源总数 | page 缺失单列 PAGE_LOCATION_MISSING |
| 耗时 | 均值/P50/P95/min/max，分段统计 | 不含 SSE 传输、前端渲染、人工等待；内存后端用独立字段 |

评测器内置纯函数计算器与自测夹具（沿用 experiment 包 ExperimentMetricsCalculator 口径），覆盖空集、单元素、多证据、无答案、半包含、次级排序等，自测先于正式评测留痕。

### 2.6 指标可测性说明（独立交付物）

`measurability-boundary.md`：逐指标写明可测/受限/BLOCKED/NOT RUN 的触发条件、证据与用户可见表现；明确无网络、无密钥、无 PG、仅本地 Embedding 各场景能跑什么；说明 BLOCKED 与 FAIL 的本质区别（环境缺失 vs 系统错误），以及跨后端（PGVECTOR vs IN_MEMORY_LOCAL）结果不可直接比较的口径。

---

## 3. 工程深度与安全专项

### 3.1 引用与来源契约

1. sourceId（稳定=真实 documentId）与 citationId（CitationDto.id=请求级 S1/S2…）逐题核对映射，禁止混用。
2. 引用信息只来自本轮检索元数据：excerpt 必须是真实内容子串、page/pageNumbers 与元数据一致；模型自报页码/路径/URL 不作证据。
3. URL 协议黑名单（与 UrlSafetyPolicy 一致）：仅允许 http/https；拒绝 opaque URL（javascript: 等）、相对路径、userinfo（防 trusted.com@127.0.0.1 视觉欺骗）、localhost、IPv4 回环/私网（10/8、172.16/12、192.168/16）、链路本地 169.254/16（含云元数据 169.254.169.254）、0/8、127/8、组播/保留 224+、前导零 IP；IPv6 拒绝回环/未指定/链路本地/site-local、ULA fc00::/7、组播 ff00::/8、zone id；IPv4-mapped IPv6 按 IPv4 规则判定。含危险协议夹具，确认 API/UI 不呈现虚构 URL。
4. 同步与 SSE 出口共用 CitationDto：同数据集对照 `/api/v1/rag/answer`（ChatController 同步路径）与 ChatStreamController + sse-parser.js/SsePayloads，字段语义不一致即 FAIL。
5. 前端安全渲染（本仓库无现成 Node 测试，不强制新建）：静态扫描 app.js/templates.js，确认无 innerHTML 直接拼接、使用 replaceChildren/textContent、外链带 rel="noopener noreferrer"、不渲染无来源 URL；若新增 Node 测试须作为独立交付物并在状态文件登记。

### 3.2 拒答、澄清与无证据路径

- 逐题比对 expectedDecision 与实际 status/decision/reasonCode/decisionDetail 四元组；拒答文案不含堆栈、上游报文、连接串、密钥。
- 无答案硬案例覆盖"词面相近实体不同"，验证不因关键词重叠强行作答（对应 NO_EVIDENCE/LOW_RELEVANCE）。
- CLARIFY 用例核验 clarificationQuestion 与 missingInformation 一致；CONFLICT 核验 conflictGroups 与被拒证据 ID。

### 3.3 注入、冲突与不可信证据

1. 注入用例 ≥2：指令型载荷按普通文本处理、不执行、不改变行为、不回显系统提示，出现执行迹象即 FAIL。
2. 原始记录逐来源标注信任级别（生效/废弃/草稿/未知，对齐 RetrievalHitView.lifecycleStatus）；"分别存在但无法证明归属"不得判有效。
3. 冲突用例期望 EVIDENCE_CONFLICT 且不调用生成，保留零调用双证据（日志 + 原始记录）。

### 3.4 删除竞态与批量核验

1. "检索后删除再返回"用例：响应前核验命中 CITATION_TARGET_DELETED/归属不一致等可区分原因码，全失效时抑制答案。
2. 批量核验逐来源输出原因码并计数，禁止单点核验后整批放行。
3. 脚本只操作本迭代 documentId/表；正式表 vector_store 与历史数据只读；禁止删表、删卷、Docker prune。

### 3.5 确定性与跨机器复现（第一质量目标）

1. 基线永久留存 `docs\evidence\D37\baseline\v1.0\`，后续参数实验与同一基线对比。
2. 质量类字段（命中、相关、决策、标签、次级排序顺序）跨机器逐字节一致；耗时只报分布，不进基线对比键。
3. 确定性措施：固定顺序、预热不纳入、单线程、本地 Embedding、显式超时、重试不伪装成功；时间戳只存元数据区。
4. 二次运行自动 diff 质量类字段，全一致才允许写入基线；IN_MEMORY_LOCAL 与 PGVECTOR 基线分别建目录，不混算。

---

## 4. 验收标准

### 4.1 四态（逐用例、逐指标）

PASS：与期望一致且证据可复算；FAIL：期望与实际不符或校验拦截；BLOCKED：环境/依赖缺失无法执行；NOT RUN：当前模式不覆盖。四态计数进入汇总，合计等于用例总数。

### 4.2 Level 1–5

| Level | 内容 | 达成条件 |
|---|---|---|
| L1 | 环境探测与模式判定（含 2×2 七场景） | env-probe 与真实环境一致，降级正确，后端三态标记准确 |
| L2 | 数据集与冻结清单 | 20 问合法、四场景齐全、manifest 哈希可复验 |
| L3 | 离线指标与计算器自测 | 自测全 PASS，指标从原始记录复算一致（含内存后端） |
| L4 | 失败标签与可测性边界 | 9 类标签原因码均有真实用例，measurability 与实际一致 |
| L5 | 全量真实端到端 | 真实模型 + 真实 pgvector 跑通；无密钥时 L5 标 NOT RUN 注明前置条件，离线以 L4 为天花板，禁止伪称 |

### 4.3 逐条用例（状态文件逐项落表）

| 编号 | 用例 | 期望 |
|---|---|---|
| C01 | mock + PG 可达 | 离线模式，检索/拒答/静态引用可测，生成类 NOT RUN，后端 PGVECTOR |
| C02 | PG 不可达但 Embedding 可用 | 内存后端 IN_MEMORY_LOCAL，指标可测带标记，退出码 0 |
| C03 | Embedding 初始化失败 | 后端 BLOCKED，检索类 BLOCKED，静态类继续，退出码 2 |
| C04 | 20 问 schema 校验 | 四场景各 5，字段完整，非法 JSON 被拒 |
| C05 | manifest 哈希复验 | 重算 SHA-256 一致，篡改可发现 |
| C06 | 可答用例（含多证据） | 期望文档/chunk 命中，ANSWER（全量）/ NOT RUN（离线生成） |
| C07 | 无答案用例（含词面相近硬案例） | 受控拒答，reasonCode 匹配，零模型调用 |
| C08 | 故障码/术语用例 | 期望术语出现在真实片段，缺失即 MISS_RECALL |
| C09 | 边界模糊用例 | CLARIFY/REFUSE/CONFLICT 与标注一致 |
| C10 | 注入用例 ×2 | 载荷不执行不泄密，行为同普通文本 |
| C11 | 危险协议/路径 URL 夹具 | 全拦截，API/UI 无虚构 URL，静态扫描通过 |
| C12 | 同步/SSE 契约对照 | 字段语义与 citation 映射一致 |
| C13 | 删除竞态用例 | 原因码可区分，全失效抑制答案 |
| C14 | 超时用例（可控参数） | 按阶段标记 TIMEOUT，后续继续 |
| C15 | 计算器自测 | 夹具全 PASS 后才允许正式评测 |
| C16 | 内存后端次级排序 | 同分数按稳定 chunkId 排序，跨进程顺序一致 |
| C17 | 模式识别 2×2 七场景 | 无/有密钥×无/有 PG + Embedding 失败 + DB 连接失败 + 模型超时，全部判定正确 |
| C18 | 二次运行一致性 diff | 质量类字段逐字节一致，两后端基线分目录不混算 |

### 4.4 熔断规则

- 同一根因连续失败 2 次：停止该链路，持久化现场与原因码，等待人工确认；禁止换写法绕过、无限重试。
- 基础设施类与数据类错误分别计数，不混为同一根因。
- 真实模型调用仅限全量且最小必要：离线零调用；全量每问至多 1 次，受控失败不重试生成。

---

## 5. 成本与 Token 预算（不伪称预算）

| 项 | 规则 |
|---|---|
| 离线/内存后端 | DeepSeek 0 调用、Token 0、零外网 |
| 全量模式 | 20 问 × 至多 1 次调用；预热轮不调模型 |
| 密钥管理 | 仅环境变量注入，禁止写入文件/PATH/日志；脱敏保持开启 |

---

## 6. 交付物清单

| # | 交付物 | 路径 |
|---|---|---|
| 1 | 评测器（含内存降级后端）+ 一条命令包装器 | docs\temp_docs\D37\evaluator\、run-evaluation.ps1（保留不删） |
| 2 | 20 问固定问题集 v1.0 | docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl |
| 3 | 冻结清单与环境探测 | dataset-manifest.json、env-probe.json |
| 4 | 第一版基线（永久只读，按后端分目录） | docs\evidence\D37\baseline\v1.0\：baseline.csv、summary.md、raw-results.jsonl |
| 5 | 指标可测性说明 | measurability-boundary.md |
| 6 | 失败标签规则（9 类） | failure-label-rules.md |
| 7 | 计算器自测证据 | metrics-selftest.txt |
| 8 | 两份技术文档（D37-XX） | docs\proj_docs\D37-01-…全量信息与操作.md、D37-02-…技术文档说明与实践指南.md |
| 9 | 迭代状态文件 | docs\evidence\D37\iteration_records\D37_state_R01.md |
| 10 | 运行日志 | logs\D37\（surefire-reports 不提交；target/ 已忽略） |

Git：仅本迭代新增证据/文档与经授权源码修改；提交前 `git status` 核实，不 push；历史表与无关文件零改动。

---

## 7. 状态文件模板（D37_state_R01.md）

```markdown
# D37 迭代状态
## 需求编号
D37-R01（RAG 评测脚本与基线）
## 基线核实
- git 基线：4eec962（执行时复验）；工作区：干净/已修改（明细）
- 双基线：CURRENT_RUNTIME_BASELINE=<实测值>；EVAL_CONTROLLED_BASELINE=<受控值>
- 环境探测：MySQL/Redis/PG 布尔值；模式：离线/全量；检索后端：PGVECTOR/IN_MEMORY_LOCAL/BLOCKED
## 数据集冻结
- 问题集 v1.0：20 问四场景各 5；manifest 哈希前缀；语料/Embedding 版本绑定（local-hash-ngram-v1/2026.11）
## 评测执行
- 预热/正式轮次：3/N；原始记录条数（含 retrievalBackend）；退出码
- 四态：PASS n / FAIL n / BLOCKED n / NOT RUN n（合计=用例总数）
- Level：L1..L5 逐项状态及证据指针
## 基线结果
- 按后端分列关键指标；误拒率/误答率；一致性 diff 结果；标签分布（9 类×原因码）
## 真实失败与修复
- 根因/修复文件/验证结果（区分已执行通过/已执行失败/未执行）
## 遗留与边界
- NOT RUN 前置条件、跨后端不可直接比较项、跨机器复现结论、D38 对接方式
```

---

## 8. 官方文档溯源

- Spring AI Reference - RAG | Spring AI 参考-RAG：https://docs.spring.io/spring-ai/reference/api/rag.html
- Spring AI Reference - Vector Databases | Spring AI 参考-向量数据库：https://docs.spring.io/spring-ai/reference/api/vectordbs.html
- Spring AI Reference - PGvector Vector Store | Spring AI 参考-PGvector 向量存储：https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html
- Spring AI Reference - Embedding Model API | Spring AI 参考-Embedding 模型 API：https://docs.spring.io/spring-ai/reference/api/embeddings.html
- pgvector - Open-source vector similarity search for Postgres | pgvector-PostgreSQL 开源向量相似度搜索：https://github.com/pgvector/pgvector

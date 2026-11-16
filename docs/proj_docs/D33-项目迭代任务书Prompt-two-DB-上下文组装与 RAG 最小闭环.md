# D33 项目迭代任务书 Prompt：上下文组装与 RAG 最小闭环

> 使用方式：本文件作为附件投喂给代码 Agent（Codex / 豆包），自包含全部上下文，不依赖外部对话记忆。
> 执行范式：**本地脚本出事实 · Agent 做工程判断 · 结构化记录生文档**。禁止全仓库扫描、禁止读完整日志、禁止凭记忆写文档。

---

## 0. 任务元信息（冻结参数，禁止修改）

| 项 | 值 |
|---|---|
| 项目根目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D33` |
| 模块目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D33\D33_CAARMCL` |
| 父 POM 扫描入口 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D33\pom.xml` |
| 技术基线 | JDK 21、Spring Boot 3.5.16、Spring AI **1.1.8**（BOM 统一管理，子模块不写版本）、Maven 3.9.16、本地仓库 `D:\SoftwareInstall\Apache\repository` |
| 技术文档输出 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\proj_docs\` |
| 证据目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\evidence\D33\` |
| 临时脚本/文本 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\docs\temp_docs\D33\`（**禁止自动删除**） |
| 日志目录 | `E:\AI\project\AI-Learning-two-lp\M3-Java-ds\logs\D33\` |
| 迭代状态记录 | `docs\iteration_records\D33_state_R01.md`（每切片一份） |
| 仓库基线 | 远程 `https://github.com/storyzhangli/M3-Java-ds`（master），基础骨架 commit `bec595e497d76aa7e5a572bd4fc5753dca644cb3`（已核实=本地 HEAD）；实施前先 `git status` 核对，不假设一致 |
| 触发指令 | `项目迭代：上下文组装与 RAG 最小闭环` |

### 0.1 本次迭代目标

打通 **文档导入 → 向量检索 → 上下文组装 → 大模型回答** 的最小 RAG 闭环，返回「回答内容 + 来源列表 + 检索状态」，并保留**无证据拒答**能力。

### 0.2 明确不做

- 不扩展完整 Agent、MCP、复杂多轮对话、重排序、混合检索。
- 不引入新的向量库或 AI 框架（LangChain4j 不引入）。
- 不修改 D30–D32 其他模块；不做公网部署。
- 不允许模型凭空生成设备知识、伪造引用，或把 AI 生成内容当成知识库证据。

---

## 1. 现状基线（已实扫，实施前先核对，禁止臆造）

### 1.1 已有可复用能力（本项目为扁平命名，无 document 包，不要找错类）

| 能力 | 现有类（位于 `com.zl.d33` 各子包） | 复用方式 |
|---|---|---|
| 文档导入 | `KnowledgeDocumentService`、`MybatisKnowledgeDocumentRepository`、`KnowledgeDocumentParser`、`KnowledgeDocumentController` | 直接复用 |
| Chunk 切分 | `DocumentChunker`、`KnowledgeDocumentChunkingService`、`StableChunkIdGenerator`、`ChunkingProperties` | 直接复用 |
| Embedding | `ChunkEmbeddingService`、`LocalHashNgramEmbeddingModel`、`EmbeddingModelConfiguration`、`EmbeddingModelCatalog`、`EmbeddingVectorValidator` | 直接复用 |
| 向量入库 | `DocumentVectorIndexService`、`KnowledgeVectorStoreService`、`PgVectorAccess`、`PgVectorStoreConfiguration`、`VectorChunkRepository`、`VectorReconciliationService` | 直接复用 |
| **真实向量检索** | **`SimilarityRetrievalService`**（注入 `VectorStore` + `QueryEmbeddingService`）、`QueryEmbeddingService`、`RetrievalFilterBuilder`、`RetrievalHitMapper` | 本次检索通道接这里 |
| 向量空间身份 | `VectorSpaceIdentity`（按 provider 生成空间标识）、`VectorSpaceIdentity`、`EmbeddingLengthUnit` | 用于禁止混用不兼容向量 |
| 重试 | `model/ModelAttemptRetryPolicy`、`ModelRetryBackOffPolicy`、`ModelFailureClassifier` | 直接复用 |
| 引用校验 | `knowledge/CitationRegistry`（contextBlock、引用标记校验、剥离未知标记） | 上下文组装/回答校验复用 |
| 编排骨架 | `knowledge/KnowledgeChatOrchestrator`（已具备 sync/stream 编排与 CitationRegistry 集成） | 参考其结构 |
| 公共契约 | `common/Result`、`common/GlobalExceptionHandler` | 直接复用 |

### 1.2 必须正视的三个现状事实

1. **`knowledge/KnowledgeRetrievalService` 是 JSON 内存检索，不是向量检索**：它只依赖 `KnowledgeCorpus`（加载 `equipment-fault-corpus.json`），未接 VectorStore、未做 Query Embedding。**不得把它当作本次向量检索通道**。
2. **真实 pgvector 通道是 `SimilarityRetrievalService`**：它注入 `VectorStore` + `QueryEmbeddingService`，已实现 Top-K 与过滤。本次 RetrievalService 必须基于它，不沿用内存检索作为主路径。
3. **测试目录当前为空**：`src/test/` 下仅有 `application-test.yml`（另有一个前端 `sse-parser.test.js`），无任何 Java 测试类。测试类需从零新建，不得宣称“沿用既有测试”。
4. **旧编排器空检索仍调用模型（问题 B，已代码实证）**：`KnowledgeChatOrchestrator` 第 65–70 行，`registry.hasSources()` 为 false 时仍执行 `aiChatService.chat()`（旧设计是普通问答兜底，非 bug）。本次 RAG 链路必须改为无证据直接拒答、不调模型，但**保留旧路径不破坏**（新接口并存）。

### 1.3 维度与空间身份（已实测，禁止改动）

- `application.yml` 中 Embedding 与 PG 维度均为 `${...:384}`，本迭代固定 **384 维**，并带 `index-version: d33-local-384-v1`。
- 换模型/换维度必须经 `VectorSpaceIdentity` 生成新空间身份，**禁止同表混存不同维度或不同模型的向量**。
- 距离度量固定 **COSINE**（两处 `distance-metric: COSINE`，索引 HNSW + `vector_cosine_ops`）；`similarity-threshold` 为余弦相似度下限、框架内部换算为距离，排序须按真实分数方向，**不得伪造分数或排序依据**。

### 1.4 pom 已就位的关键依赖（无需新增）

`spring-ai-starter-vector-store-pgvector`、`spring-ai-starter-model-deepseek`、文档读取器（pdf/markdown/jsoup/tika）、`postgresql`、`reactor-netty-http`、`spring-boot-starter-data-redis`、MyBatis-Plus、`spring-boot-starter-test`。实施前先核对，若实际缺失再说明，禁止擅自加版本号。

---

## 2. 目标架构与职责边界

### 2.1 五个组件（保持职责分离）

| 组件 | 职责 | 硬性边界 |
|---|---|---|
| **RagOrchestrator** | 协调检索、组装、生成全流程；不承载具体检索/模型细节 | 只做编排，不直接写 VectorStore 调用或 Prompt 拼接 |
| **RetrievalService** | Query Embedding → VectorStore → Top-K，返回结构化检索结果 | **禁止直接调用 ChatModel** |
| **ContextAssemblyService** | Chunk 去重、排序、长度预算控制、来源组织 | 只处理已检索 Chunk，不触发新检索 |
| **GenerationService** | Prompt 构造 → ChatModel → 生成回答 | **禁止直接访问 VectorStore** |
| **RagResponse** | 回答内容 + 来源列表 + 检索状态 + 生成状态 | 数据对象，不含业务逻辑 |

组件间通过明确数据对象交互（如 `RetrievedChunk`、`AssembledContext`、`RagResponse`），不传递底层 `VectorStore`、`ChatModel` 等框架对象，**禁止用无约束 Map 作为核心业务契约**；检索过滤字段必须来自现有真实数据结构，不得假设字段存在。

### 2.2 不可破坏的边界、注入防护与状态契约

1. RetrievalService 不直接调用 ChatModel；GenerationService 不直接访问 VectorStore。
2. 空检索（结果为空或低于阈值）→ 直接返回受控“无可用证据”，**不调用模型编造答案**，并保留可核验的 ChatModel 零调用证据。
3. **Prompt Injection 防护**：检索资料视为非可信数据，资料中的指令性文本不得覆盖系统指令；模型不得编造文档、页码、链接或来源编号，证据不足时明确说明无法确认。
4. **RAG 状态枚举**（按 `ResultCode` 风格落地）：`NO_HIT`（检索成功无命中）、`NO_USABLE_CONTEXT`（有结果但无可用上下文）、`RETRIEVAL_FAILED`（检索异常）、`GENERATION_FAILED`（生成失败）、`SUCCESS`。

### 2.3 编排落地策略

- 现有 `KnowledgeChatOrchestrator` 已具备 sync/stream 与 `CitationRegistry` 集成骨架，可参考；但检索入参必须切换为 `SimilarityRetrievalService` 的真实结果。
- 改造现有 Orchestrator 还是新建 `RagOrchestrator`，在修改清单中说明理由，**优先最小改动、保留既有内存语料路径不被破坏**（开关或新接口并存，不强制替换）。

---

## 3. 强制执行流程（五阶段）

### 阶段一：脚本化环境探测

写 PowerShell 探测脚本到 `docs\temp_docs\D33\probe_d33.ps1`，输出落盘 `docs\evidence\D33\00-env-probe.txt`。只采集事实：`mvn -v`、`git status --short`、`git log --oneline -5`、PG/pgvector 容器与连接可用性（只报存在性，不打印口令）、`PgVectorEnvironmentVerifier` 结果、模块目录与 pom 关键依赖核对。Agent 只读探测输出，不遍历全仓库。

### 阶段二：按需精读与设计

- 只读第 1.1 节列出的直接关联类与新增服务对应数据对象；跨模块读取须说明业务关联。
- 输出修改清单：新增/修改文件、复用能力、配置项、影响范围、高风险点。
- 明确上下文预算口径（Token 或字符，复用 `EmbeddingLengthUnit`）、Top-K 与相似度阈值来源（无实测依据时写为可配置项，不硬编码承诺值）。

### 阶段三：增量实现

- 新增 `ContextAssemblyService`（去重、排序、预算截断、来源组织）与 `GenerationService`（Prompt 构造、模型调用、回答与引用回传）。
- 新增 RetrievalService 真实向量实现（接 `QueryEmbeddingService` + `SimilarityRetrievalService` Top-K）。
- 新增最小 RAG HTTP 接口及请求/响应对象（含可选检索过滤条件）。
- 配置全部外置：模型名、Top-K、预算、阈值、base-url 走配置/环境变量；**API Key 仅环境变量或程序实参注入，禁止硬编码与提交 Git**。

### 阶段四：测试与验证

- 测试必须真实执行；完整日志落盘 `logs\D33\`，Agent 只读 surefire 摘要 + 首个失败根因堆栈。
- 离线单测用 Mockito mock VectorStore / ChatModel，不依赖真实上游；真实链路用开关保护（无开关则 skip）。
- 执行命令：
  `& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -f "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D33\pom.xml" clean test`

### 阶段五：证据与文档

- 每个切片写 `docs\iteration_records\D33_state_Rxx.md`（模板见第 5 节）。
- 最终两份文档仅基于迭代记录与测试证据生成，禁止从对话历史回忆拼接。

---

## 4. 端到端测试矩阵与分级验收

### 4.1 九类必测场景（全部覆盖）

| 测试场景 | 验收要求 | 建议测试类 |
|---|---|---|
| 文档可答 | 检索到相关知识，回答有证据支持，来源可追溯 | `RagAnswerableEndToEndTest` |
| 无关问题 | 不把无关知识强行拼成答案，不凭常识补造设备知识 | `RagIrrelevantQuestionTest` |
| 空检索 | 返回 `NO_HIT`，且验证 ChatModel 零调用（可核验调用次数） | `RagEmptyRetrievalTest` |
| 无可用上下文 | 检索有结果但全被过滤/预算无法容纳，返回 `NO_USABLE_CONTEXT`，不调模型 | `RagNoUsableContextTest` |
| 模型超时 | 超时可识别，返回 `GENERATION_FAILED`，不伪装成正常回答 | `RagGenerationTimeoutTest` |
| 向量库异常 | 基础设施异常返回 `RETRIEVAL_FAILED`，不伪装成空检索 | `RagRetrievalFailureTest` |
| 引用不存在 | 模型返回未分配的 `[S99]`，非法引用被清理，来源列表无虚假来源 | `RagInvalidCitationTest` |
| 重复 Chunk | 检索结果含重复记录，上下文不重复拼接 | `RagDuplicateChunkTest` |
| 预算超限 | 内容总量超预算，最终上下文不超出预算且来源映射不丢 | `RagBudgetOverrunTest` |

### 4.2 额外检查项（逐条断言，不得省略）

- 来源与实际检索且进入上下文的 Chunk 一致（documentId/chunkId 对得上）。
- 上下文不超出预算：边界用例覆盖恰好等于预算、超预算被截断且来源映射不丢。
- 模型不引用不存在的来源（伪造标记由 `CitationRegistry` 剥离并计数）。
- 检索成功但无结果 vs 检索失败、检索失败 vs 生成失败，状态码可区分。
- AI 生成内容不被回写为知识库证据；重复片段已去重；旧版本/失效文档不进入上下文。
- 模型调用测试使用可控超时，不依赖无限等待；超时分支可稳定复现。

### 4.3 分级验收（Level 1–5，未达等级不得宣称完成）

| 等级 | 验收条件 |
|---|---|
| Level 1 | 依赖解析、编译、单元测试通过 |
| Level 2 | 文档导入/向量入库能力复用验证通过 |
| Level 3 | 上下文去重、排序、预算控制及来源映射测试通过 |
| Level 4 | GenerationService、Prompt 约束、无证据拒答及模型异常处理通过 |
| Level 5 | 真实 PostgreSQL/pgvector + 实际 ChatModel 完成九类端到端场景，证据与来源可追溯 |

**Mock 不得替代真实端到端验收**；真实模型/数据库不可用时标记阻塞并记录，不得用 Mock 冒充通过。

### 4.4 预算与来源的诚实约束

- 预算口径 Token 与字符分离：**字符估算不得伪称 Token 数**；无 Token 计数能力时采用可解释的保守估算并注明误差边界。
- 禁止静默裁剪破坏证据内容或来源标识；裁剪情况须记录。
- 无法验证模型逐条引用时，只声明"提供给模型的证据来源"，**不得声称这些来源均被模型实际引用**。

---

## 5. 今日必须交付的工程材料

1. 最小 RAG HTTP 接口及请求、响应对象（含来源列表与检索/生成状态字段）。
2. 端到端演示脚本：从文档导入开始，演示向量检索与回答，落 `docs\temp_docs\D33\`，输出留痕到 `docs\evidence\D33\`；脚本不硬编码密钥、不破坏现有数据。
3. **RAG 完整调用链时序图**：Mermaid `sequenceDiagram`，参与方含 Client、RagController、RagOrchestrator、RetrievalService、QueryEmbeddingService、VectorStore、ContextAssemblyService、GenerationService、ChatModel；体现正常流程、空检索跳过 ChatModel 分支、检索异常与模型超时分支；节点/消息文本英文双引号，Typora 0.11.18 兼容。
4. 技术设计与测试记录，放入 `docs\proj_docs\` 与 `docs\evidence\D33\`。
5. 实际 Maven 测试结果及日志（真实命令、真实通过/失败/skip 数）。

### 迭代状态记录模板（`D33_state_Rxx.md`）

```markdown
# D33 迭代状态
## 需求编号
D33-R01
## 修改文件
- 新增：RagOrchestrator / RetrievalService / ContextAssemblyService / GenerationService
- 配置：application.yml；测试：对应测试类
## 设计决定
- 检索通道接 SimilarityRetrievalService 真实 pgvector 链路，不沿用 JSON 内存检索为主路径。
- 空检索直接拒答、不调用生成服务；预算口径采用 Token/字符（注明）。
## 测试
- RagAnswerableEndToEndTest：通过；RagEmptyRetrievalTest：通过（零调用已核验）
- 全量：Tests run: X, Failures: 0, Errors: 0, Skipped: N
## 证据
- logs/D33/mvn-test-D33-R01.txt；docs/evidence/D33/00-env-probe.txt
## 遗留问题
无 / 具体描述与影响
```

### 最终两份技术文档（命名 D33-XX，落 docs\proj_docs\）

1. **`D33-01-上下文组装与RAG最小闭环-项目迭代全量信息与操作.md`**：需求、架构、修改清单、测试结果、证据路径、Git diff 摘要——面向"做了什么、怎么复现"。
2. **`D33-02-上下文组装与RAG最小闭环-技术文档说明与操作实践指南.md`**：RAG 原理（检索/组装/生成职责与数据流）、向量空间身份与维度一致性、Prompt 与引用约束、预算控制、无证据拒答、配置说明、常见坑与排障——面向"为什么这么做、下次怎么用"。

---

## 6. 数据安全与工程边界

- 禁止 Docker prune、删除数据卷、重建现有数据库容器等破坏性操作；禁止批量删除/覆盖历史文档与向量数据。
- 禁止启用 `remove-existing-vector-store-table` 或应用启动自动删除向量表；禁止改动 pgvector 容器端口映射与数据卷。
- 不提交数据库密码、模型 API Key 或其他敏感信息；不暴露内部异常堆栈与连接信息。
- 不修改 D30–D32 历史快照及无关模块；不自动删除 `docs\temp_docs\D33` 下临时文件。
- 数据库、Embedding 或 ChatModel 不可用时，停止对应真实链路验收并记录阻塞，不得伪造成功。

## 7. 熔断与 Token 治理

- 同一根因连续 2 次修复失败 → 强制输出《根因诊断记录》（现象/已试方案/差异/下一步假设），停止盲目迭代。
- 测试失败且原因不明 → 只读第一个失败用例堆栈，不把完整日志喂回。
- 禁止全仓库 grep、读完整构建日志、重复输出架构分析、多 Agent 重复扫描同一模块；预算不构成跳过工程工作的理由。

## 8. 验收清单（完成后自检）

- [ ] 最小 RAG 接口真实可调，RagResponse 含回答、来源、检索/生成状态字段。
- [ ] 九类端到端场景全部有真实测试，空检索验证 ChatModel 零调用且可核验。
- [ ] Prompt Injection 防护生效、五状态枚举可区分；未启用 `remove-existing-vector-store-table` 等自动删表配置。
- [ ] 达到 Level 5 或如实标注阻塞等级；Mock 未冒充真实端到端。
- [ ] 无证据拒答生效；回答引用来源均能在实际检索 Chunk 中找到。
- [ ] 预算 Token/字符口径诚实、不伪称；裁剪有记录、来源映射不丢。
- [ ] 维度固定 384、距离度量 COSINE（HNSW + vector_cosine_ops），经 VectorSpaceIdentity 隔离，无混用不兼容向量。
- [ ] `mvn clean test` 本机真实执行，摘要落盘，结果可复现。
- [ ] 无真实 API Key 入库；时序图、演示脚本、两份 D33 文档交付，命名规范。
- [ ] `docs\temp_docs\D33\` 临时脚本保留未删；Git diff 仅含 D33 模块与 docs/，无其他模块改动。

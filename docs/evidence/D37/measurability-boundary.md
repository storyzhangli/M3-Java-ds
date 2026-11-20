# D37 指标可测性边界说明

> 版本：v1.0（与评测器 v1.0、基线 v1.0 绑定）
> 本文说明每个指标在何种环境/模式下"可测 / 受限 / BLOCKED / NOT RUN"，以及四态（PASS/FAIL/BLOCKED/NOT RUN）的语义边界。

## 1. 四态语义（先于指标口径）

| 态 | 含义 | 退出码贡献 | 典型场景 |
|---|---|---|---|
| PASS | 期望与实际一致且证据可复算 | 计入通过 | QA-001 命中且 ANSWER |
| FAIL | 期望与实际不符或校验拦截 | 计入失败 | QA-003 期望 ANSWER 实际 CLARIFY |
| BLOCKED | 环境/依赖缺失无法执行 | 不计失败 | Embedding 初始化失败 |
| NOT RUN | 当前模式不覆盖 | 不计失败 | 离线模式下的"真实模型引用正确性" |

**本质区别**：BLOCKED 是**环境缺失**（无 PG/无 Embedding/无必要语料），不是系统错误；FAIL 是**系统行为与标注不符**（在环境就绪前提下）。两者不得混算。

## 2. 逐指标可测性矩阵

| 指标 | 离线（mock+PG） | 离线（mock+内存后端） | 全量（deepseek+PG） | 无 PG 且 Embedding 失败 |
|---|---|---|---|---|
| HitRate@K / Recall@K / Precision@K / 噪声占比 | 可测（PGVECTOR） | 可测（IN_MEMORY_LOCAL，带后端标记） | 可测 | BLOCKED |
| 检索耗时（retrievalElapsedMillis） | 可测（真实库内） | 受限（inMemorySearchMs 单独命名，不标库内） | 可测 | BLOCKED |
| Embedding 耗时（queryEmbeddingMs） | 可测（本地 embedding） | 可测 | 可测 | BLOCKED |
| 端到端耗时（totalElapsedMillis） | 可测（含检索+RAG 组装） | NOT RUN（无 RAG 组装） | 可测 | BLOCKED |
| 元数据完整率 | 可测（元数据来自真实检索响应/冻结语料） | 可测 | 可测 | 可测（静态，从冻结语料） |
| 拒答正确率 / 误拒率 / 误答率 | 可测（RAG 判题零模型调用） | NOT RUN（无 RAG 判题） | 可测 | NOT RUN |
| 澄清/冲突路径 | 可测 | NOT RUN | 可测 | NOT RUN |
| 真实模型引用合法率 | NOT RUN（mock 无真实引用） | NOT RUN | 可测 | NOT RUN |
| 注入载荷不执行/不泄密 | 可测（离线判题路径零模型调用，注入只当普通文本） | 受限（仅测检索干扰，不测生成） | 可测 | NOT RUN |

## 3. 各环境能跑什么（实测结论）

| 环境 | 检索后端 | 能测 | 不能测 |
|---|---|---|---|
| 无密钥（AI_MODE=mock） + PG 可达 + 应用启动 | PGVECTOR | 检索质量、检索/embedding/端到端耗时、元数据、拒答/澄清/冲突、静态引用规则、注入不执行 | 真实模型引用正确性、生成质量 |
| 无密钥 + PG 不可达 + 本地 Embedding 可初始化 | IN_MEMORY_LOCAL | 检索质量（带后端标记）、内存段耗时、元数据（来自冻结语料） | RAG 判题、库内耗时、生成 |
| 无密钥 + 本地 Embedding 初始化失败 | BLOCKED | 静态探测（env-probe）、语料/问题集哈希校验 | 全部检索类指标 |
| 有密钥 + ChatModel + PG 可达 | PGVECTOR（全量） | 上述全部 + 真实引用、拒答质量、端到端 | — |

## 4. 跨后端比较口径（禁止直接比较）

PGVECTOR 与 IN_MEMORY_LOCAL 的指标**不可直接比较**，原因：

1. **检索语义不同**：PGVECTOR 走真实 pgvector（HNSW 近似最近邻，可能有近似误差）；IN_MEMORY_LOCAL 走内存暴力全量余弦（精确排序）。
2. **耗时口径不同**：PGVECTOR 的 `retrievalElapsedMillis` 含 PG 查询往返；IN_MEMORY_LOCAL 的 `inMemorySearchMs` 是进程内内存耗时（本版实测 <1ms，取整为 0ms），**禁止**将内存耗时标注为数据库耗时。
3. **可测范围不同**：PGVECTOR 可测 RAG 判题（decision/status），IN_MEMORY_LOCAL 不产生判题（BOUNDARY 5 题在该后端为 NOT RUN）。
4. **基线分目录**：`baseline/v1.0/PGVECTOR/` 与 `baseline/v1.0/IN_MEMORY_LOCAL/` 独立，不得混算。

如必须跨后端比较检索质量，应只比较"文档级 HitRate / chunk 级 Recall / Top-1 错位"等与耗时无关的确定性字段，并明确注明后端差异。

## 5. 质量类字段与耗时字段的复现边界

- **质量类字段（进基线对比键，跨机器逐字节一致）**：命中数、相关数、documentHit、chunkHit、actualDecision、actualStatus、reasonCode、labels、hitDocumentIds、hitLogicalChunkIds、次级排序顺序。
- **耗时字段（只报分布，不进基线对比键）**：queryEmbeddingMs、retrievalElapsedMillis、totalElapsedMillis、inMemorySearchMs。跨机器/跨次运行的耗时天然波动，不参与一致性 diff。
- **时间戳（只存元数据区）**：生成时间、运行目录时间戳，不进入原始记录的对比字段。

## 6. BLOCKED 与 FAIL 的判定边界（本版实测）

- BLOCKED 判定：强制 PGVECTOR 但 PG 端口或应用 HTTP 不可达 → `retrievalBackend=BLOCKED`，20 题全 BLOCKED，退出码 2。本版实测：停掉应用后 `-Backend PGVECTOR` 复现，四态 `{BLOCKED=20}`，退出码 2。
- FAIL 判定：环境就绪但系统行为与标注不符（如 QA-003 期望 ANSWER 实际 CLARIFY）。本版 PGVECTOR 后端四态 `{PASS=14, FAIL=6}`，退出码 1。
- NOT RUN 判定：当前模式不覆盖（如 IN_MEMORY_LOCAL 后端的 BOUNDARY 5 题，无 RAG 判题）。本版内存后端四态 `{PASS=15, NOT RUN=5}`，退出码 0。

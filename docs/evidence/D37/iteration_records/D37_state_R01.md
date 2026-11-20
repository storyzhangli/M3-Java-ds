# D37 迭代状态

## 需求编号
D37-R01（RAG 评测脚本与基线）

## 基线核实
- git 基线：`4eec9625f9068157cfdf873635196ae3f9a6d305`（复验一致）
- 工作区：干净（无已跟踪文件修改）；未跟踪新增：`docs/evidence/D37/`、`docs/proj_docs/D37-01-*.md`、`docs/proj_docs/D37-02-*.md`
- CURRENT_RUNTIME_BASELINE：chunkSize=480 / overlap=60 / topK=5 / similarity-threshold=0 / PG 表 vector_store / Embedding local-hash-ngram-v1·2026.11·384 维·COSINE / index-version d37-local-384-v1
- EVAL_CONTROLLED_BASELINE：BaseUrl=http://127.0.0.1:8080 / DatasetVersion=v1.0 / Rounds=3 / Warmup=3 / Backend=显式传入 / Threshold=0.0 / TopK=5
- 环境探测：MySQL 3306=True、Redis 6379=False、PG 192.168.200.153:5432=True、8080=启动后 True；模式=offline（AI_MODE=mock）；检索后端=P兼任三态（PGVECTOR / IN_MEMORY_LOCAL / BLOCKED 均实测复现）

## 数据集冻结
- 问题集 v1.0：20 问，四场景各 5（QA/QU/QT/QF），含 2 条注入（QA-005 混合、QU-004 纯）
- manifest 哈希：questions=`896bceaa…`、corpus=`aad6ac4d…`、evaluator=`72cda994…`（首跑已回填）
- 绑定：local-hash-ngram-v1 / 2026.11 / 384 维 / COSINE / index-version d37-local-384-v1

## 评测执行
- 自测：11/11 PASS（metrics-selftest.txt）
- 预热/正式轮次：3/3；原始记录条数=60（20 问 × 3 轮，含 retrievalBackend）
- 退出码：IN_MEMORY_LOCAL=0；PGVECTOR=1；BLOCKED=2

### 四态（round=1，20 问）

| 后端 | PASS | FAIL | BLOCKED | NOT RUN | 合计 |
|---|---|---|---|---|---|
| IN_MEMORY_LOCAL | 15 | 0 | 0 | 5 | 20 |
| PGVECTOR | 14 | 6 | 0 | 0 | 20 |
| BLOCKED | 0 | 0 | 20 | 0 | 20 |

### Level 1–5

| Level | 状态 | 证据 |
|---|---|---|
| L1 环境探测与模式判定 | 达成 | env-probe.json；三后端三态实测复现 |
| L2 数据集与冻结清单 | 达成 | dataset-manifest.json 三哈希可复验 |
| L3 离线指标与计算器自测 | 达成 | metrics-selftest.txt 11/11 PASS；指标从原始记录复算 |
| L4 失败标签与可测性边界 | 达成 | failure-label-rules.md；真实触发 8 个细分原因码；measurability-boundary.md 与实际一致 |
| L5 全量真实端到端 | NOT RUN | AI_MODE=mock 无密钥，真实模型调用不覆盖（前置条件：AI_API_KEY + deepseek + PG 可达） |

## 基线结果（按后端分列）

### IN_MEMORY_LOCAL
- HitRate@K：ANSWERABLE 1.0000、TERMINOLOGY 1.0000、UNANSWERABLE 0.0000（零误命中）
- Recall（Macro）：ANSWERABLE 1.0000、TERMINOLOGY 1.0000
- 一致性 diff：质量类字段跨轮逐字节一致

### PGVECTOR（真实系统）
- HitRate@K：ANSWERABLE 0.4000、TERMINOLOGY 1.0000、UNANSWERABLE 0.0000
- Recall（Macro）：ANSWERABLE 0.8000、TERMINOLOGY 1.0000
- 误拒率：ANSWERABLE 0.2000、TERMINOLOGY 0.8000；误答率：0.0000
- 拒答正确率：UNANSWERABLE 1.0000、BOUNDARY 1.0000
- 一致性 diff：质量类字段跨轮逐字节一致

### 标签分布（PGVECTOR，round=1）
- MISS_RECALL_FILTERED=1（QA-005 注入干扰）
- HIGH_NOISE_PRECISION=18、HIGH_NOISE_TOP1=3
- REFUSAL_FALSE_NEGATIVE=5（QA-003、QT-002~005）、REFUSAL_WRONG_CODE=2
- CLARIFY_UNEXPECTED=7、CLARIFY_EXPECTED_MISSING=1
- METADATA_PAGE_LOCATION_MISSING=1
- 未触发（NOT RUN，如实标注）：FABRICATED_CITATION、TIMEOUT、MODEL_ERROR、RETRIEVAL_ERROR

## 真实失败与修复（本轮编码过程，均已执行通过）

| # | 问题 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| 1 | Metrics 编译错：RawRecord 无 expectedChunkIds/expectedDocumentIds | RawRecord 缺两字段 | 补字段 + buildRecord 传参 | javac 通过 |
| 2 | Evaluator 编译错：actualStatus 未找到 | label() 缺 actualStatus 形参 | label 加形参 + 调用传参 | javac 通过 |
| 3 | Main 编译错：RawRecord 构造参数数不符 | BLOCKED 分支少两字段 | 补两 List.of() | javac 通过 |
| 4 | 运行时 NPE：actualDecision 为 null | 内存后端无判题，checkConsistency 直接 .equals | 改 Objects.equals | 内存后端跑通 |
| 5 | 内存后端误标 MODEL_ERROR_EMPTY×20 | 四信号全 null 仍进模型错误判定 | hasRagSignal 守卫 | 重跑后标签正确 |
| 6 | 延迟统计 inMemorySearchMs 样本=0 | 内存检索<1ms 取整为 0，被 filter(v>0) 过滤 | 未测量字段用 -1 哨兵 + filter(v>=0) | 重跑后样本=20 |

## 逐条用例（C01–C18）

| 编号 | 用例 | 状态 | 说明 |
|---|---|---|---|
| C01 | mock+PG 可达 | 达成 | PGVECTOR 后端，离线模式，生成类 NOT RUN |
| C02 | PG 不可达但 Embedding 可用 | 达成 | IN_MEMORY_LOCAL 后端，退出码 0 |
| C03 | Embedding 初始化失败 | 达成 | BLOCKED 后端，退出码 2（以"停应用+强制 PGVECTOR"复现环境不可用） |
| C04 | 20 问 schema 校验 | 达成 | Phase2 冻结时校验四场景各 5 |
| C05 | manifest 哈希复验 | 达成 | 三哈希可复算 |
| C06 | 可答用例（含多证据） | 达成 | QA-001/002/004 PASS；QA-003/005 FAIL 已归因 |
| C07 | 无答案用例（词面相近硬案例） | 达成 | QU-001~005 受控拒答，拒答正确率 100% |
| C08 | 故障码/术语用例 | 达成 | QT-001 PASS；QT-002~005 被过度澄清 FAIL 已归因 |
| C09 | 边界模糊用例 | 达成 | QF-001~005 受控不答，拒答正确率 100% |
| C10 | 注入用例 ×2 | 达成（部分） | 注入不执行不泄密达成；QA-005 暴露检索漂移（重要发现） |
| C11 | 危险协议/路径 URL 夹具 | 未覆盖 | 本轮评测器未实现 URL 夹具专项（UrlSafetyPolicy 已存在，属 D35 交付，不在本轮范围） |
| C12 | 同步/SSE 契约对照 | 未覆盖 | 本轮只测同步 /api/v1/rag/answer，未测 SSE 流式路径 |
| C13 | 删除竞态用例 | 未覆盖 | 离线 mock 无删除场景 |
| C14 | 超时用例（可控参数） | 未触发 | 超时参数已实现（检索 30s/端到端 60s），真实评测未超时 |
| C15 | 计算器自测 | 达成 | 11/11 PASS，先于正式评测留痕 |
| C16 | 内存后端次级排序 | 达成 | 自测第 5 项验证同分 chunkId 升序 |
| C17 | 模式识别 2×2 七场景 | 部分达成 | 三后端三态复现；DB 连接失败/模型超时细分未逐一复现 |
| C18 | 二次运行一致性 diff | 达成 | checkConsistency 实现，三后端跨轮质量字段一致 |

## 遗留与边界
- NOT RUN 前置条件：L5 全量需 AI_API_KEY + deepseek + ChatModel + PG 可达；FABRICATED_CITATION/TIMEOUT/MODEL_ERROR/RETRIEVAL_ERROR 标签需全量或异常环境触发。
- 跨后端不可直接比较：PGVECTOR（HNSW 近似 + 库内耗时）vs IN_MEMORY_LOCAL（暴力精确 + 内存耗时），基线分目录不混算。
- 跨机器复现结论：本地 Embedding + 固定顺序 + 单线程 + 稳定次级排序，质量类字段可逐字节复现；耗时字段天然波动，不进基线对比键。
- D38 对接：①EquipmentClarificationPolicy 过度澄清调优；②检索前注入清洗（QA-005 漏召回）；③补齐 URL 夹具/SSE 契约/删除竞态/超时专项；④有密钥后升级 L5 全量端到端。

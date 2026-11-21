# D38-R02 混合检索实验可测性边界（measurability-boundary）

> 原则（H7）：能测则测、不可测标 N/A 并写明原因，禁止填 0 或定性冒充定量。

## 本轮可测且已测（PASS 项）

| 域 | 项 | 证据 |
|---|---|---|
| 检索质量 | 20 问 ×2 策略全指标（HitRate/Recall/Precision/噪声/标签） | runs + report/metrics-matrix.csv |
| 确定性 | 两 run 质量投影逐字节 diff；倒排指纹跨构建一致 | runs/consistency + c15-index-rebuild.json |
| 决策路径 | REFUSE/CLARIFY/ANSWER 七态与原因码（mock 决策链真实执行） | runs + security-cases.json |
| 隔离 | 开关零差异（C04/C05）、三轨零触碰（C06：台账 SHA 不变+行数不变） | startup/regression/isolation |
| 成本 | 检索分段耗时、倒排构建与统计量 | cost-analysis.json |

## N/A / NOT RUN 清单与原因

| 项 | 状态 | 原因 |
|---|---|---|
| 真实生成质量与引用伪造类标签 | NOT RUN | mock 模式（app.ai.mode=mock）；FABRICATED_CITATION 整组 mocked=true 规则性排除（D37 先例） |
| 设备型号分场景（用户原始需求 D3） | N/A | 语料无任何设备型号正例（KP 实读核实）；禁止造题动冻结集 |
| CONFLICT 自然触发 | N/A | 数据集 notes 明示真实语料不可自然触发冲突路径 |
| two_ 轨混合实验 | NOT RUN | §0.3：预算余 1 次不动用（台账 SHA 证明零调用）；L5 链路未建 |
| SSE / 前端一致性 | NOT RUN | 本轮范围外（§0.3） |
| 跨机复现（L5） | NOT RUN | 单机环境；投影一致性与指纹机制是跨机可复现的构造性保证 |
| 倒排序列化字节数 / JVM 堆增量 | N/A | IndexInfo 未实现该统计字段（P1.4 实现缺口）；30 行量级定性可忽略，不估算填充；R03 候选补齐 |
| keywordSearchMs/fusionMs 精确分布 | 受分辨率限制 | System.currentTimeMillis 毫秒粒度，58/60、60/60 条亚毫秒记 0；如需 µs 级改 NanoTime 口径（R03 候选） |
| 残留噪声行不可区分性 | 边界如实 | d36exp 副本与原始 DOC 文本相同，BM25 分数必同分，排序仅由 chunkId tie-break 决定；任何「文本正确性」结论不得基于副本/原件区分（两组的命中归属解释力有限） |
| PG 不可达降级 | 设计为 BLOCKED | 倒排必须镜像真实表行集，无内存 fallback（D4 控制变量要求）；与 D37 IN_MEMORY_LOCAL 语义不同，故意不测（触发即熔断非降级） |

## 小样本警示

每场景仅 5 题（QA/QU/QT/QF），单题驱动的变化（本轮即 QA-005 修复、QA-002 排序改善共 2 题）
不构成统计学结论；决策规则阈值（+5pp/≥2 案例）正是为此设置的保守线。QU 误检、QF 翻案
等安全项按「零新增」硬判据而非比例判据。

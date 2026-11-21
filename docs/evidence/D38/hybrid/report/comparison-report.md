# D38-R02 混合检索对比报告与落地决策（C16）

> 编制时间：2026-11-21 22:55。全部数字由 `runs/hybrid-20261121-2250/` 原始记录复算
> （复算脚本口径见 report/per-question-ab.csv 与 metrics-matrix.csv），无任何手填值。
> 决策规则引用任务书 §P6.6 冻结判据，禁止事后改线——本报告逐条对账。

## 1. 实验有效性前提（全部达成后才解读对比）

| 门禁 | 结果 | 证据 |
|---|---|---|
| C04 开关隔离 | PASS：enabled=false 时 hybrid 端点 code=5000（NoResourceFound 语义）、零装配日志；enabled=true 正常装配（docs=30, terms=1045, postings=5770） | startup/c04-smoke-notes.txt |
| C05/P3 基线回归 | **BASELINE_REGRESSION_PASS**：最终 jar enabled=false 全量重跑 D37 评测器，60/60 条质量投影与冻结基线逐字节一致（含 FAIL 6 题原标签） | regression-final/consistency-with-D37-baseline.txt |
| C10 主矩阵 | 240 条原始记录（20 问×3 轮×2 跑×2 策略），请求错误 0，ABAB 交错 | runs/ |
| C11 确定性 | PURE 与 HYBRID 各自两 run 质量投影逐字节一致 | runs/consistency/consistency-*.txt |
| C06 三轨隔离 | 台账跑前/跑后 SHA 逐字节一致（17 条，余 1 未动）；vector_store=30、two_vector_store=8 不变；hybrid 源码零 MySQL/写路径引用 | isolation/ |
| H3 控制变量 | 同数据集（SHA 896b…34bec 实测断言）、同表行集、同 LOCAL 384、同 topK=5/threshold=0、同评测器口径；唯一变量=检索策略 | Main 启动断言 + env-probe |

## 2. 总体结果

| 指标 | A=PURE_VECTOR | B=HYBRID_RRF | Δ |
|---|---|---|---|
| 题级 PASS/FAIL | 14/6 | **15/5** | +1（QA-005 FAIL→PASS） |
| 整体 MacroRecall | 0.9000 | 1.0000 | **+10.00pp** |
| QA HitRate@5(文档) | 0.4000 | 0.8000 | +40.00pp |
| QA MacroRecall | 0.8000 | 1.0000 | +20.00pp |
| QA microPrecision@5 | 0.3333 | 0.3750 | +4.17pp |
| QT/QF/QU 全指标 | — | 与 A 逐位相同 | 0（零回归） |
| MISS_RECALL_FILTERED 标签 | 1 | 0 | -1 |
| 新增失败标签 | — | 0 类 | 0 |
| 检索段平均耗时 | 3.82ms | 5.08ms(+keyword≤1+fusion≤1) | +33% |
| 端到端平均耗时 | 26.72ms | 30.80ms | +15.3% |

A 组与 D37 冻结基线逐题一致（14/6、QA 0.4/0.8/0.3333、误拒 0.2、QT 误拒 0.8 全部复现），
对照组自证可信；B 组提升完全可归因于检索策略单变量。

## 3. 案例与诚实记录

- **提升案例 1（漏召修复）：QA-005** —— 期望片段纯向量 rank7 出不了 top5（真实漏召，
  MISS_RECALL_FILTERED），BM25 rank3 经 RRF 拉回融合 rank4；recall 0→1，RRF 值与手算
  逐位一致（retrieval/rrf-fixtures.json）。详见 report/cases/qa005-recall-fix.md。
- **提升案例 2（排序口径单列）：QA-002** —— documentHit False→True（chunkHit 原本已兜底
  PASS，属排序改善非漏召修复）。
- **未修复（如实）：QA-004** —— documentHit 两组均 False，混合检索未改变该题文档面排序。
- **退化案例：0 个（如实记录）** —— B 组标签零新增、QU 误检零新增、残留行命中占比反而
  由 81.6% 降至 75.5%（其中 d34-real 注入残留 2→0，小样本方向性观察，不夸大）。
- 已知基线缺陷不被掩盖：QT 误拒率 0.8、QA-003 误拒 0.2 属**决策层 CLARIFY 行为**，
  与检索策略无关，B 组原样复现——混合检索救不了不该它救的问题。

## 4. 风险实测（任务书 §3.5/§P6.5 逐项）

| 风险项 | 实测结论 |
|---|---|
| d36exp 同文本副本 BM25 不可区分 | 存在但被机制吸收：同分由 chunkId 升序 tie-break 稳定裁决，两 run 逐字节一致；命中归属解释力受限时结论仅基于质量字段（measurability-boundary §残留） |
| 中文连写/数字粘连（「偏差超过20」类） | 倒排按 isLetterOrDigit 连续段+n-gram（与 LocalHash 同规则），20 问集未出现因此产生的漏配实例；边界属理论存在，夹具层已锁行为（KeywordTokenizerTest 9 用例） |
| bigram/trigram 跨词伪匹配 | 未观测到伪匹配进入 top5 并引发 FAIL（B 组 HIGH_NOISE_PRECISION 与 A 持平=18 记录级，无放大） |
| E5→w:e5 负例 | QF-005 B 组仍 REFUSE（未翻案，硬红线守住） |
| QU-003 词面相近硬案例 | B 组 CLARIFY 与 A 一致，误检未增加 |
| 注入放大 | 「32108」探针：关键词路 0 命中，融合全 VECTOR_ONLY——注入串无 BM25 加分路径 |

## 5. 落地决策（引用冻结判据逐条对账）

建议保留（Recommend RETAIN）。§P6.6「建议保留」五条件全部满足：

1. 整体 MacroRecall Δ≥0：**+10.00pp** ✓
2. （QA-005 漏召修复 **或** Precision Δ≥+5pp **或** 误拒率下降）：**QA-005 已修复** ✓
   （Precision +4.17pp 未达 +5pp、误拒率持平——OR 分支满足，如实并列）
3. QU 误检 / QF-005 翻案零新增：**0/0** ✓
4. 平均耗时增量 ≤100%：**+33%（检索段）/ +15.3%（端到端）** ✓
5. 无新增注入/引用类 FAIL：**零新增** ✓

决策含义与边界（防止过度解读）：

- 「保留」= hybrid 分支代码保留在仓库、**开关维持默认 false**（H2），不接前端、不进主链路；
  本数据集 20 问小样本下结论是方向性的（measurability-boundary 已警示）。
- 增益来源已定位=词面精确匹配修正向量排序（QA-005 fusionTrace 实证），与选型预期一致；
  若未来语料/题库扩展，需按同流程重跑（评测器与门禁已工具化可复用）。
- 遗留缺口（不阻塞决策，R03/D39 候选）：IndexInfo 序列化字节/堆增量字段未实现（N/A 已标）；
  keyword/fusion 计时亚毫秒分辨率受限；QT 误拒 0.8 属决策层，另行治理；OPT 消融 B1/B2
  本轮未排期未跑（不进决策，如实 NOT RUN）。

## 6. 复算入口（任何人可验证本报告的每个数字）

```powershell
# 评测（含自测门禁与一致性熔断）
pwsh docs\temp_docs\D38\hybrid_evaluator\run-hybrid-evaluation.ps1 -BaseUrl http://127.0.0.1:8081 -OutputBase <新目录>
# 回归门禁
# 见 regression-final\d37-regression-console.txt 首部命令记录
```

原始记录：`runs/hybrid-20261121-2250/run{1,2}/{PURE_VECTOR,HYBRID_RRF}/raw-results.jsonl`
（各 60 条，投影文件 projection.txt 同目录，跨 run 逐字节一致）。

# D38_state_R02 —— 混合检索实验（关键词+向量+RRF）

- 迭代/切片：D38 / R02；基线 commit：`abdd132c8b11ba8dbcd601324330b4dbbb701ef0`（编制时 HEAD，本轮提交见 git）
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / PG 18.7 + pgvector 0.8.7
- 冻结参数：keywordRoute=IN_PROCESS_INVERTED featureSubset=w+b+t bm25=(k1=1.2, b=0.75) rrfK=60 candidateTopN=10 topK=5 threshold=0 tableRowCount=30
- 数据集哈希（Main 启动断言，磁盘 manifest 现读现比）：questions `896bceaa…234bec`、corpus `aad6ac4d…35ab0b`
- 偏差裁决：D1 沿用基线参数（无冲突）/ D2 基线=LOCAL 锚定 D37（同 run 复验 14/6 逐位一致）/ D3 型号 N/A（语料无正例，禁造题）/ D4 倒排镜像全表（30 行含残留，执行时实读确认）

## 门禁与用例落表

| 编号 | 状态 | 证据路径（相对 docs\evidence\D38\hybrid\） | 备注 |
|---|---|---|---|
| KP1 关键词路线 | PASS | probe\ | PG 默认 parser 中文连写局限→进程内倒排；两候选均满足，取 IN_PROCESS_INVERTED |
| KP2 行集一致 | PASS | probe/kp2-* | 倒排 docs=30=vector_store 行数；fingerprint 跨构建/跨实例一致 |
| KP3 分词夹具 | PASS | probe/kp3-* + KeywordTokenizerTest | 与 LocalHash 同规则受控复制，9 用例锁定 |
| KP4 语义探针 | PASS | probe/kp4-* | KEYWORD_ONLY 真实存在（"CNC-3000"=2 条）；score=null 裁决沿用 |
| C01 编译+单测 | PASS | compile-test.txt、compile/ | 36/36（含既有 11 用例零回归）；评测器 javac 一次通过 |
| C02 探测门禁 | PASS | probe\ 全套 | 先探测后编码，参数先冻结后评测 |
| C03 倒排行集 | PASS | probe/kp2-* + 启动日志 | fingerprint `a6a6bd4f…e31b07` 启动=重建响应 |
| C04 开关零差异 | PASS | startup/c04-* | off：端点 5000+零装配日志；on：装配正常 |
| C05 基线回归 | PASS | regression-final/consistency-with-D37-baseline.txt | 最终 jar 60/60 逐字节一致，BASELINE_REGRESSION_PASS（regression\ 为中间 jar 过程证据，同 PASS） |
| C06 三轨隔离 | PASS | isolation\ | 台账 17 条前后 SHA=`9A620A58…E10E`；vector_store 30/two_vector_store 8 不变；hybrid 源码零 MySQL/写路径（无客户端，grep 口径注明） |
| C07 检索契约 | PASS | retrieval/c07-* | topK 越界/空查询→4001；data 17 字段清单 |
| C08 RRF 正确性 | PASS | retrieval/rrf-fixtures.json + evaluator-selftest.txt | 5 条响应≡手算逐位一致；镜像 SelfTest 11/11 |
| C09 分数语义 | PASS | retrieval/c09-keyword-only-sample.json | KEYWORD_ONLY vectorSimilarity=null 无伪造；scoreSemantic=RRF |
| C10 主矩阵 240 条 | PASS | runs/hybrid-20261121-2250\ | ABAB 交错，requestError=0 |
| C11 确定性 | PASS | runs/…/consistency/ | 两策略跨 run 投影逐字节 CONSISTENCY_PASS |
| C12 分场景矩阵 | PASS | report/metrics-matrix.csv | 含 QF-005 单列、残留占比 |
| C13 案例溯源 | PASS | report/cases/qa005-recall-fix.md | 提升口径分列（漏召修复 vs 排序改善）；QA-004 未修复如实 |
| C14 注入/拒答 | PASS | report/security-cases.json | 7 案 6 PASS + 引用伪造类 NOT RUN（mocked 规则性排除） |
| C15 成本 | PASS（2 项 N/A） | report/cost-analysis.json | 序列化字节/堆增量=IndexInfo 未实现（P1.4 缺口，R03 补） |
| C16 决策 | PASS=保留 | report/comparison-report.md | 五判据逐条对账 |
| C17 密钥卫生 | PASS | security/key-scan.txt | 证据+评测器+日志零命中；FIXTURE_ADDED=0 |
| C18 文档+Git | PASS | proj_docs\D38-03/04、本文件、git\commit-stat.txt | temp_docs 源码 add -f；out/target/surefire 未入库 |

## 分级结论

L1=PASS（编译/单测/自测） L2=PASS（双实例真实启动+两种开关态冒烟，期间修复 2 个装配缺陷见诚实声明） L3=PASS（回归门禁+主矩阵 240 条+确定性） L4=PASS（成本/安全案例/隔离） L5=NOT RUN（单机，跨机复现未做）

## 对比结论（引用矩阵数字，权威源=runs run1 题级 round=1）

- 整体：MacroRecall Δ=**+10.00pp**（0.9→1.0） Precision（QA micro）Δ=+4.17pp（0.3333→0.3750，未达 +5pp，OR 分支由 QA-005 修复满足） 误拒率 A=QA 0.2/QT 0.8 B=QA 0.2/QT 0.8（持平） FAIL 数 A=6 **B=5** 耗时增量=**+33%**（检索段 3.82→5.08ms；端到端 +15.3%；判据 ≤100% ✓）
- 分场景：QA HitRate 0.4→0.8、MacroRecall 0.8→1.0；QT/QU/QF 全指标与 A 逐位相同（零回归）；QF-005 仍 REFUSE 未翻案（硬红线 ✓）；QU 误检零新增；型号场景 N/A；残留行命中占比 A=80/98 → B=74/98（d34-real 2→0）
- 标签：MISS_RECALL_FILTERED 1→0、METADATA_PAGE_LOCATION_MISSING −1、**零新增**
- 案例：提升 2（QA-005 漏召修复【fusionTrace 实锤 rank7+kw3→融合 rank4】+ QA-002 排序改善口径）/ 退化 0（如实）/ 未修复：QA-004（documentHit 两组均 False）
- **决策：保留（RETAIN）**，开关维持默认 false、不接前端；依据=§P6.6 五判据全满足（comparison-report.md §5 逐条对账）；边界=20 问小样本方向性结论

## 成本实耗

DeepSeek calls=0 tokens=0（mock 结构性零远程）；百炼 calls=0（台账 17 条前后 SHA 一致，余 1 预算未动）；倒排=docs 30/terms 1045/postings 5770，构建 5ms（首载 91ms 含 PG RTT）；序列化字节/堆增量=N/A（IndexInfo 未实现，不估算填充）；主矩阵 45 秒/240 条零错误。

## 诚实声明

- 已执行并通过：C01–C17 全部（真编译、真启动、真回归、真 240 条、真逐字节 diff、真手算核对）；3 个 L2 装配期缺陷（多构造缺 @Autowired、裸 @RequestMapping 不被 isHandler 识别、repackage jar 锁）真实踩中→修复→重编译重验，详见 D38-03 §7。
- 已执行但失败：SelfTest 首跑 2 FAIL——根因为**手算夹具算错**（非实现错），修正断言后 11/11；执行期摘要曾把 questions SHA 尾位误记 `34bfc`，磁盘实为 `34bec`，以 manifest 现读纠正。
- 尚未执行：L5 跨机复现；OPT 消融 B1/B2（可选未排期）；two_ 端点混合链路（刻意不调以保预算）。
- N/A 及原因：设备型号分场景（语料无正例）；CONFLICT 自然触发（数据集 notes 明示不可）；FABRICATED_CITATION 组（mock 模式规则性排除）；IndexInfo 序列化字节/堆增量（字段未实现）；MySQL 行数快照（无客户端，改用源码零引用+台账 SHA 口径）。
- 方法学修正记录：评测器 LOW_SCORE 0.15 阈值仅适用余弦量纲，对 HYBRID 漏召统一归 FILTERED（防止 RRF 分~0.016 系统性误标）。
- 包装脚本 hybrid_regression.ps1 / hybrid_run-evaluation.ps1 未单独建文件，等效流程已分步执行并留全证据（D38-03 §4 可复现命令）。

## 遗留与 D39 候选

查询改写路线（与本轮互补未测）/ two_ 轨混合实验（待预算与 L5）/ 语料扩展补故障码型号正例 / 残留行清理（需授权）/ 双重 Embedding 与 µs 级计时优化 / IndexInfo 字段补齐 / Reranker / QT 误拒 0.8 决策层治理 / 跨机 L5。

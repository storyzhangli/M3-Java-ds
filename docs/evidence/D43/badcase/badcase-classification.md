# D43 bad-case 六阶段分类表

迭代：D43《RAG 端到端、权限与回归》｜分类器：`com.zl.d43.e2e.BadCaseClassifier`
规则依据：任务书 §6.4 优先级 **PERMISSION > PARSE > CHUNKING > EMBEDDING > RETRIEVAL > GENERATION**，
首个命中即 primaryStage，其余记 secondaryStages；无法归类→UNCLASSIFIED+强制人工裁决。
结构化数据：同目录 `badcase-classification.json`（11 条，含完整 observed/expected/evidencePath）。

## 1. 分类总表

| # | 记录 | 场景（轮次） | primaryStage | secondary | 现象一句话 | 修复引用 | 复测 |
|---|------|-------------|--------------|-----------|------------|----------|------|
| 1 | E2E-BCASE-01 | S06（r11-13） | GENERATION | RETRIEVAL | mock 决策层 CLARIFY_REQUIRED 抢答，"成功态"断言 FAIL | BC-19a/b | PASS |
| 2 | E2E-BCASE-02 | S07（r12） | GENERATION | — | 无证据题走 MISSING_REQUIRED_CONTEXT，非冻结等价码 | BC-19a | PASS |
| 3 | E2E-BCASE-03 | P06（r10） | PERMISSION | — | 泄漏熔断=回显 query 假阳性（数据面零泄漏） | BC-15 | PASS |
| 4 | E2E-BCASE-04 | P07/P08（r10） | PERMISSION | — | 拒绝面纯中文 msg 无 AUTH 机器码（主码可观测性缺口） | BC-16 | PASS |
| 5 | E2E-BCASE-05 | S03（r10） | EMBEDDING | — | 上轮 vector_index_task 残留触发迟到写入保护误伤 | BC-17 | PASS |
| 6 | E2E-BCASE-06 | S04/S08（r11） | **UNCLASSIFIED** | — | "SYNCED 行存在"断言 vs 实码"成功不落账"语义 | BC-18 | PASS |
| 7 | E2E-BCASE-07 | S06 生成轮（r16/17） | RETRIEVAL | EMBEDDING | hits=2 但 cos=0.1396 < relevant-score 0.15→LOW_RELEVANCE 拒 | BC-19c | PASS |
| 8 | E2E-BCASE-08 | S06 生成轮（r18） | GENERATION | — | Validator 把回显 query 的"38a"判证据外数值→GENERATION_FAILED | BC-19d | PASS |
| 9 | E2E-BCASE-09 | S06 生成轮（r19） | GENERATION | — | mock 模板零 [S 标记]→cited 恒空，"非空"系 P4 自加口径 | BC-19e | PASS |
| 10 | E2E-BCASE-10 | S09/S12 前置（r4） | EMBEDDING | — | lc-002 无人播种+捕获时序错→chunkIds 恒空 | fix 播种项 | PASS |
| 11 | E2E-BCASE-11 | 固定评估（全轮） | PERMISSION | RETRIEVAL | hitRate@5=0.0：历史 30 行 tenant=NULL fail-closed（隔离预期） | 注记 | PASS* |

\* 第 11 条为 E2E_SATURATION_NOTED 性质的口径注记（非失败态），入表以满足 G-6"全部有主阶段+证据路径"。

## 2. 六阶段分布

```text
GENERATION  4 条（01/02/08/09）—— 判定链与 mock 契约口径族，全部测试侧适配，主码零改动
PERMISSION  3 条（03/04/11）—— 其中 03/11 为观测/口径面，04 留主码遗留（D44）
EMBEDDING   2 条（05/10）—— 索引生命周期状态管理与 IT 编排
RETRIEVAL   1 条（07）—— 阈值×mock embedding 分布错配
PARSE       0 条 ——（PDF 夹具缺失属环境裁决 BC-05，未构成解析运行期 bad-case）
CHUNKING    0 条
UNCLASSIFIED 1 条（06）—— G-6 人工裁决闭环（见下）
```

## 3. UNCLASSIFIED 人工裁决留痕（G-6 强制）

**E2E-BCASE-06（S04/S08 台账断言）**

- 裁决问题：失败不属于六阶段任一运行期故障——主码删除/清理闭环执行正确（向量清零、
  生命周期收敛均可实证），仅测试断言"存在 SYNCED 行"与 D40 设计"成功不落账
  （markSyncedIfRowExists 仅收敛既有 FAILED 行）"语义冲突。
- 裁决结论：**测试侧口径缺陷**。归因链：P4 编制断言时对 D40 台账写入路径理解错误；
  主码行为以 D40 R03 权威口径为准，零改动。
- 处置：断言重释为 `noUnconvergedSync`（零 PENDING/FAILED；有行必须全 SYNCED），
  G-5 门禁语义由"全 SYNCED"调整为"零未收敛"，双标注写入 e2e-report。
- 裁决人/时间：执行 Agent 依 §6.4 人工裁决通道，2026-11-26，证据
  `docs/temp_docs/D43/mvn-e2e-round11.txt`（失败现场）→ `round20.txt`（复测 PASS）。

## 4. 门禁对照（§6.6）

| 门禁 | 结果 | 实证 |
|------|------|------|
| G-6 分类 | **闭环**：11/11 条 primaryStage+evidencePath 齐备；UNCLASSIFIED=1 条已人工裁决留痕；retestStatus 全 PASS | 本表+json |
| G-2 场景 | 无 FAIL；S02 NOT_RUN（C16 冻结）；S10 BLOCKED（round 装配事实）+演练轮 PASS 双记录 | e2e-scenario-records.json / fault-drill-observation.json |
| G-3 权限 | 10/10；泄漏=0 | permission-matrix-results.json |
| G-4 引用 | S06 白名单符合率 100%（零编造口径，BC-19e 双标注）；S12 CITATION 拒绝码正确 | 同上 |
| G-5 一致性 | S08/S09 零未收敛；S10 FAILED→retry→SYNCED→PG 0 行 | fix-records BC-18 / fault-drill-observation.json |

## 5. 修复记录索引

BC-01～BC-05（P1–P4 段正式归档）与 BC-11～BC-19（P5 段）全量四段式记录
（现象→根因→改动文件→回归结果）见 `../fixes/fix-records.md`；
BC-06～BC-10 为前段未落盘空号，已在该文件 §0 如实注记，不虚构。

# D42-02 ｜ Rerank 价值评估：验证报告与证据清单

> 基线 7c9d382（D42 骨架）→ 工作区未提交状态（本轮纪律：不 commit/不 push）。
> 汇报口径：区分"已执行并通过 / 已执行但失败后修复 / 未执行"。

## 1. 交付物清单

| 层 | 内容 | 数量 |
|---|---|---|
| 主包 | com.zl.d42.rerank（契约 8 + 实现/装配/端点 8） | 16 类 |
| 实验包 | com.zl.d42.rerank.experiment（矩阵/Runner/指标/门禁/落盘/记录） | 8 类 |
| 测试 | LocalLexical 8 例 + Contract 5 + Bailian 13 + Configuration 3 + Decision 6 + AccessBoundary 3 + 实验 IT 1 | 38 单测 + 1 IT |
| 迁移 | D41 测试 14 文件 → d42_* 口径 | 95 例 |
| 配置 | application.yml（app.rerank 段 + access-log exclude 追加）、application-test.yml、application-experiment.yml | 3 文件追加式 |

## 2. 门禁与回归实测

| 项 | 命令/口径 | 实测结果 | 状态 |
|---|---|---|---|
| 迁移回归 | `mvn -o -B -f D42\D42_RERANK\pom.xml clean test`（P2 后） | Tests run 95, F0, E0, Skipped0 | 已通过（mvn-clean-test.txt） |
| P4 全量 | 同上（新增 27 单测） | 首轮 7 红（嵌套 stubbing）→ 修复后 122/122 | 已执行并失败→修复→通过 |
| 终版回归 | 同上（含 C18 三例）**前提：注入 MYSQL_PASSWORD**（TableRecordPersistenceIntegrationTest 8 例真实 MySQL 集成依赖该 env；未注入时整类 assumption 中止，surefire 显示 Tests run 0/ Skipped 0 而非 skipped 计数） | **133/133, Failures 0, Errors 0, Skipped 0** | 已通过（mvn-clean-test-final.txt；R02 复跑双口径复验：离线 125/125 + 注 env 133/133，见 mvn-rescan-offline-125.txt / mvn-rescan-with-mysql-133.txt） |
| 实验轮 | profile=experiment + D42_EXP_RUN=true + -Dtest=RerankValueExperimentIT | 首轮契约拦截（topN=5>候选 3）→ Runner effTopN 修复 → **真实执行 12.37s 通过** | 已通过（mvn-p5-experiment-run.txt） |
| R3 远程真实轮 | 需独立授权 + DASHSCOPE_API_KEY | **未执行（BLOCKED=RERANK_REMOTE_DISABLED，按任务书预设路径）** | 未执行，如实登记 |

## 3. R0–R3 矩阵结果（Q01–Q10 × repeat3，真实 PG 检索）

| 组 | 配置 | MRR@5 | HitRate@5 | avgNoise | P50/P95 总耗时 | P95 重排 | FAIL | 零分歧 |
|---|---|---|---|---|---|---|---|---|
| R0 | 候选5 无重排 | 0.7167 | 1.00 | 6.2 | 4.20/5.37ms | — | 0 | true |
| R1 | 候选5+本地 | 0.7167 | 1.00 | 6.2 | 4.66/5.84ms | 0.74ms | 0 | true |
| R2 | 候选20+本地 | 0.7167 | 1.00 | 6.2 | 4.07/5.46ms | 0.36ms | 0 | true |
| R3 | 候选20+远程 | BLOCKED（RERANK_REMOTE_DISABLED） | | | | | | |

**门禁判定（预冻结口径，未看结果调参）**：

| 组 | G-A（ΔMRR≥0.05 或 ΔHit≥0.10） | G-B | G-C（本地≤50ms 增量） | G-E | 结论 |
|---|---|---|---|---|---|
| R1 | ✗ Δ=0.0000 | ✓ | ✓ +0.47ms | ✓ | **NOT_ADOPTED** |
| R2 | ✗ Δ=0.0000 | ✓ | ✓ +0.09ms | ✓ | **NOT_ADOPTED** |
| R3 | 不判（BLOCKED 自证，不使他组失格） | | | | **BLOCKED** |

**解读（客观）**：基线 R0 在冻结 10 题集 HitRate@5=1.0 饱和，MRR 提升空间上限≈0.28（未前置命中仅 2/10 题），词面重排未产生任何头部移动（Δ 精确为 0 而非四舍五入）。本地重排**零成本零劣化**（P95 增量<1ms，G-C 富余）——不采用是"无收益"，不是"有代价"。结论不可外推至 HitRate 未饱和语料。

## 4. 硬契约自查（H1–H9）

- H1 基线：以 7c9d382 为新基线（F01 漂移已留痕 storage-inventory.baselineNote）。
- H2 默认关：application.yml `enabled: ${RERANK_ENABLED:false}`；RerankConfigurationTest 三态矩阵实证。
- H3 白名单：仅新增 com.zl.d42.rerank**、yml 三处追加、docs/logs 目录；既有类零 diff（迁移测试为 d41→d42 机械替换件）。
- H4 空≠败：EMPTY_CANDIDATES failed()=false 单测锁定。
- H5 不截断：超限交服务端 4xx，单测 countMismatch/timeout 实证。
- H6 mock 零外呼：实验 IT 断言台账零行 + remote bean 缺席 BLOCKED 路径。
- H7 主链路零差异：95 迁移基线在 d42_* 全量复跑 = D41 报告值。
- H8 失败显式：12 原因码全分支单测覆盖（Bailian 13 例含 LEDGER_WRITE_FAILED）。
- H9 零 Key：zero-key-scan.txt 0 真凭据命中；台账/日志/结果 JSON 零全文。

## 5. 存储对账（清理前后）

| 对象 | KP2 基线 | 实验后 | 清理后 | 证据 |
|---|---|---|---|---|
| PG vector_store | 30 | 33（+d42rr-base- 3） | **30，rr_rows=0** | post-cleanup-inventory.txt |
| MySQL d42_document_sync | 0 | 3（indexChunks 审计副作用行） | **0** | 同上 |
| MySQL d41_* / kd / d42 其余 | 10/10/0 | 不变 | 不变 | 同上 |
| Redis db0 | 1 | 未触碰 | 1 | 同上 |

## 6. 过程缺陷与修复记录（真实履历）

1. 批1 写入函数 `$script:enc` 解析 null → 改函数体内构造编码（重跑成功）。
2. 迁移文件带 BOM → javac 拒绝（P2 轮已修，全轮沿用无 BOM）。
3. BailianTextReranker **重试成功不清 failCode**（真实缺陷，rateLimitRetrySuccess 单测抓出）→ callOnce 成功后 `failCode=null`。
4. 测试嵌套 stubbing → UnfinishedStubbing 7 红 → 先构造后 when。
5. Runner topN 未按候选数收敛 → 契约异常正确拦截 → `effTopN=min(finalTopK, size)`。
6. 台账扫描命中测试假 token → 夹具改 'fixture-not-a-real-key-0000' 语义自证。

## 7. 证据索引（docs/evidence/D42/）

```
probe\    rerank-capability.json / storage-inventory.json / fixture-truth.json / rerank-params.json
regression\ mvn-clean-test.txt(95) / mvn-test-after-p4.txt(122) / mvn-clean-test-final.txt(133) / mvn-p5-experiment-run.txt
extract\  rerank-experiment-matrix.json / rerank-results.json(90 run 明细) / rerank-summary.json / rerank-decision.json
cleanup\  post-cleanup-inventory.txt
security\ zero-key-scan.txt / access-boundary.txt
iteration_records\ D42_state_R01.md
```

## 8. 未决与后续建议

- **远程 R3 真实轮**：代码路径与单测已就位（13 例），待授权后 `RERANK_ENABLED=true RERANK_REMOTE_ENABLED=true DASHSCOPE_API_KEY=...` 重跑 IT，G-D 费用按台账实算（单价 0.8 元/百万 tok，预算 ≤0.02 元/题）。
- **本轮不 commit**：工作区含 26 个新 main 文件 + 8 测试文件 + 3 yml + 证据树，等待用户指令。
- D41 R03 遗留输入（大表断裂/列粘连/首行升格）不在 D42 范围，维持为后续迭代候选。
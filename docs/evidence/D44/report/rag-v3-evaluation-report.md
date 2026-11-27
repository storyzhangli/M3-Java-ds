# RAG v3 评测报告（D44 复证轮）

范围：双口径回归 × e2e 两轮 × 语义 diff × 四类专项 × 固定评估｜mock 轮（H4：远程调用=0）
判定权威源：`../milestone/rag-v3-milestone.md`（M1–M8 全 PASS，RAG v3 成立）。

## 1. 双口径回归（R-A/R-B）

| 口径 | 命令要点 | 结果 | 说明 |
|------|----------|------|------|
| R-A 完整 | mvn -o clean test（MYSQL/PG 在线） | **158/158 BUILD SUCCESS** | 含 e2e 门关闭时全量 |
| R-B 离线 | 同去 MYSQL_PASSWORD | 150/150 + 8 Assumption 中止 | TableRecordPersistenceIntegrationTest 整类按设计让位；DV-02：任务书"125+8"为 D39 期残留口径，150+8=158 自洽 |

## 2. e2e 场景与演练（R-C/R-D，门控 d44.e2e.run/drill）

- **S 矩阵**：11 PASS + S02 NOT_RUN（C16 冻结）+ S10 BLOCKED（主配置装配面）→ 与 D43 终态口径恒等。
- **权限矩阵**：10/10，跨租户 LEAK=0。
- **演练轮**：FAILED→retry→SYNCED 收敛、vectorsGone=true（drill json）。
- **修复前置**：BC-21（sync/audit version int→varchar(32) 回归，DDL 面已修 VERIFIED）。

## 3. 语义 diff 对 D43 基准（M3）

| 件 | 结果 | 说明 |
|----|------|------|
| e2e-scenario-records | NAMING_ONLY(5) | d43e2e→d44e2e 夹具/表名等值映射（显式登记） |
| permission-matrix | NAMING_ONLY(1) | 同上 |
| fixed-eval | **TIMING_ONLY** | 15 题指标逐值恒等（elapsedMs 白名单） |
| fault-drill | NAMING_ONLY(1) | 同上 |
| SEMANTIC_DIFF / STRUCT_DIFF | **0 / 0** | 门禁达成 |

## 4. 固定评估集（R-F）与饱和注记（原文保留）

- 指标：**hitRate@5 = mrr@5 = 0.0（15 题）**——与 D43 基准恒等复现。
- 归因（非退化）：历史 30 行向量 tenant=NULL fail-closed（O-09 授权项）+ relevant-score 0.15×
  mock embedding 分布错配（O-16）。
- **saturationNote 原文**（D43 件→D44 复现）：`"未饱和（与 D42 基线差异见 badcase 分类）"`。
- 任务书 §6.4 "HitRate@5=1.0 饱和注记原文"与实码基准不符——以 D43 件原文为准登记（禁按愿景改写）。

## 5. 四类专项（R-E，活体实例面）

| 专项 | 结论 | 关键数值 |
|------|------|----------|
| V1 引用真实 | PASS（BC-19e 双标注） | answerable=0（CLARIFY 安全态）；零违例；IT 轮 S05/S06/S08 引用链+11 单测承接 |
| V2 删除同步 | **PASS 7/7** | DELETE→SYNCED→PG 0→检索零命中→audit ALLOWED→零引用 |
| V3 权限过滤 | **PASS**（6+EVIDENCE_REF） | userB hits=[]（跨租户 0 泄漏）、AUTH_DISABLED/EXPIRED 4001、命中守恒 |
| V4 无证据拒答 | **PASS 5/5** | REFUSE/LOW_RELEVANCE×4 + 4001×1，零参数泄漏 |
| BC-22 标注 | 活体启动需 `--app.rerank.enabled=true` 绕行（O-07）；行为面零影响（LocalLexicalReranker 主链零消费实证） |

## 6. 演示页冒烟（C18）

knowledge.html/JS 200、index 导航就位；health/search(hits=5)/answer(SUCCESS+answer 411 字+
sources 完整 CitationDto)/sync 链真实返回；citedSources=0 属 BC-19e mock 设计态注记；零新端点。

## 7. 清理与卫生（P7）

- 存储：doc 18 / principal 5 / sync 3 / vci 30 / task 23 / PG 30 / Redis DBSIZE=1——9/10 表精确回 P0；
  audit 233→78 走 DEV-03（O-13/O-02）。
- 凭据扫描：TOTAL=4 全 WHITELIST（env 注入/本地测试口令），**CANDIDATE=0**；百炼 key 全环境变量。
- 冻结终测：AGG_MAIN/AGG_TEST 复扫逐字一致（18:41）。

## 8. 遗留与下轮输入

- D45 首位：O-02（测试库隔离）+ O-07（BC-22 装配修复）；授权项：W-1/O-09/O-10/O-11。
- 语义质量主张边界：本报告全部结论为 **mock/契约级**；真实模型轨评测完成（C16）前，
  不得对外表述为"语义质量达标"。

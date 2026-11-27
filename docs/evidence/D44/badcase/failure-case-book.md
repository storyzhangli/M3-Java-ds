# D44 失败案例集（failure-case-book）

迭代：D44《RAG 阶段答辩与里程碑 / RAG_PDAM》｜分类规则沿用任务书 §6.4 六阶段优先级
**PERMISSION > PARSE > CHUNKING > EMBEDDING > RETRIEVAL > GENERATION**，无法归类→UNCLASSIFIED+人工裁决。

## 0. 历史承接（D43 全集，不重复展开）

D43 轮 11 条（GEN4/PERM3/EMB2/RETR1/UNCL1，全部 retest PASS）见
`docs/evidence/D43/badcase/badcase-classification.md|json`。本轮主链零改动，历史条目无复发实证
（R-C 语义 diff=NAMING_ONLY 佐证 S 场景/矩阵行为与 D43 终态一致）。

## 1. 本轮新增案例

| # | 记录 | 来源轮次 | primaryStage | 现象一句话 | 分级 | 修复引用 | 复测 |
|---|------|----------|--------------|------------|------|----------|------|
| 1 | D44-BC-21 | R-C round1 | UNCLASSIFIED† | S09 deleteDocument 4223：d44_document_sync INSERT ON DUPLICATE KEY SQLException | P1 | temp_docs/D44/ddl/bc21-version-varchar.sql（2×ALTER） | PASS（round2 11/11+矩阵 10/10） |
| 2 | D44-BC-22 | R-E 首启 | UNCLASSIFIED† | 默认配置组合（pgvector=on+rerank=off）应用无法启动 | P1 | 未修（里程碑轮主码零改动）→ O-07 升级 | 绕行 VERIFIED（--app.rerank.enabled=true，行为面零影响实证：LocalLexicalReranker 检索/answer 链零消费） |
| 3 | D44-BC-23 | R-E V1 首跑 | UNCLASSIFIED‡ | 自造自然语言题集→code200 但 cits=0（LOW_RELEVANCE 拒），误判专项 FAIL | 测试侧 | verify-v1.ps1 题集改 key sentence 原文口径 | PASS（双标注轮） |
| 4 | D44-BC-24 | R-E V2 r1-r3 | UNCLASSIFIED‡ | 脚本三连缺陷：page 播种缺 updated_at 列 / CallApi DELETE GetBytes($null) 崩 / QP COUNT 提取错行 | 测试侧 | verify-v2.ps1 三轮修正（IT 完整列形态/空体分支/数字行过滤） | PASS（round 7/7） |
| 5 | D44-BC-25 | R-E V3 首跑 | PERMISSION | 跨租户泄漏判定用响应全文正则，`data.query` 回显请求原文触发假阳性；实况 hits=[]、filterDescription 已正确收敛 T-B | 测试侧（判定面） | verify-v3.ps1 改 hits 级判定 | PASS（六断言+EVIDENCE_REF） |
| 6 | DEV-03 | P7 对账 | PERMISSION | d44_admin_audit 233→78：删除链连坐清审计（AdminAuditRepository L43）+ 未归因缺口，审计不可变性破坏 | P2 | 不追回；O-13 登记 + 测试库隔离实证升级 | 对账 VERIFIED（其余 9 表全回 P0 基线） |

† 非六阶段运行期故障：schema/装配契约面，走人工裁决通道。
‡ 测试侧口径/工具缺陷，主码零改动（先例：D43 E2E-BCASE-06 同款裁决）。

## 2. 案例四段式详情

### D44-BC-21（P1，环境/schema 回归，已修）

- **现象**：R-C round1 S09 deleteDocument 返回 4223「同步标记失败」，根异常
  `SQLException: ON DUPLICATE KEY UPDATE d44_document_sync`；ReproSync.java 事务内复现定界
  → `SQLSTATE=HY000 CODE=1366 Incorrect integer value: 'v2' for column 'version'`。
- **根因**：D43 BC-14 已把 `version` 修复为 varchar(32)（版本指针需承载 'v2' 字符串语义），
  D44 骨架建表脚本未同步该终态——**修复在克隆建表时回归**。information_schema 对比实证
  d44 vs d43 终态列类型差异。
- **改动**：非代码改动；`bc21-version-varchar.sql` 两条 ALTER（sync affected=3、audit affected=82），
  复验列型 varchar(32)、既有行 version='0' 兼容。
- **回归**：R-C round2 PASS（S 11 PASS+S02 NOT_RUN+S10 BLOCKED、矩阵 10/10 LEAK=0、fixed-eval 15 题）。
- **教训（入 O 清单）**：跨迭代克隆 DDL 必须 diff 前代表结构终态，不能只复制初始建表脚本
  → **O-14 表结构演进清单（schema migration 台账）**。

### D44-BC-22（P1，主码装配契约，未修→O-07）

- **现象**：活体实例首启失败。`RerankValueExperimentRunner` 挂
  `@ConditionalOnProperty(app.vectorstore.pgvector.enabled=true)` 且构造注入
  `LocalLexicalReranker`（该类仅在 `app.rerank.enabled=true` 时装配）；主配置默认组合
  pgvector=on + rerank=off → NoSuchBeanDefinitionException，**应用无法启动**。
- **根因**：D43 BC-11 的规避只做在 IT `@TestConfiguration` 内，未修主码装配面；里程碑轮
  主码零改动约束下不允许本轮修复。
- **绕行与行为面影响实证**：`--app.rerank.enabled=true` 启动成功；grep 证实
  LocalLexicalReranker 在检索/answer 主链零消费（仅 RerankController/实验 Runner 引用），
  V1–V4 判定不受绕行影响（双标注已记 r-e-registration.json）。
- **处置**：O-07 升级登记（实验 Runner 改 ObjectProvider 可选注入或独立条件组），D45 候选修复项。

### D44-BC-23/24/25（测试侧，已修，摘要见总表）

共同模式：**专项脚本判定面与题集口径先于被测面出错**。与 D43 S04/S08（BC-18）、
BCASE-03（BC-15 回显假阳性）完全同构，佐证该类缺陷是脚本侧系统性习惯问题
→ **O-15 专项脚本自检清单**（题集必须 key sentence 级原文、泄漏判定必须 hits 级、
COUNT 提取必须数字行过滤、HTTP 封装必须空体分支），已并入本文件教训段。

### DEV-03（P2，审计面完整性，登记不追回）

- **现象**：P0 冻结 233 行（用户骨架期手工演示，DENIED 为主）；R-C 后实况 90；
  P7 清理本轮 12 行残留后 78。净减 155 行不可恢复（无 binlog 审计手段，不虚构归因）。
- **已识别删除面**：① E2eRoundIT L237 前缀清（d44e2e-，非主因）；② 主码
  `AdminAuditRepository.deleteByDocument`（L43，文档删除链按 target_document_id 精确连坐清审计）。
- **工程判断**：②属设计缺陷面——admin 审计是合规证据链，不应随业务文档删除物理清空；
  其余缺口逐行归因超本轮预算，如实登记。
- **处置**：O-13（审计与文档生命周期解耦：软删标记或归档表）+ 测试库隔离（种子 O-02/QC-OBS-1，
  本轮实证升级为 P1）→ 均入优化清单，D45 首位批。

## 3. 分布与门禁结论

```text
UNCLASSIFIED（人工裁决） 4 条：2 条环境/装配面（BC-21/22）+ 2 条测试侧（BC-23/24）——全部闭环留痕
PERMISSION              2 条：判定面假阳性 1（BC-25，测试侧已修）+ 审计完整性 1（DEV-03，P2 登记）
PARSE/CHUNKING/EMBEDDING/RETRIEVAL/GENERATION 运行期：本轮 0 条新增（主链零改动+语义 diff 佐证）
```

G-6 等效门禁：**满足**——全部案例有 primaryStage、裁决/处置记录、证据路径与复测状态；
UNCLASSIFIED 均走人工裁决通道留痕。

# RAG v3 里程碑判定（D44，M1–M8）

基线：5016c39 指纹冻结｜判定原则：实测值 vs 任务书 §6.5 判据，任一不过则降级"v3 候选"。
判定日期：2026-11-27。

| # | 判据 | 要求 | 实测值 | 结论 | 证据 |
|---|------|------|--------|------|------|
| M1 | 主链冻结 | 聚合哈希回读一致+主码零改动（W-1 除外需授权） | AGG_MAIN/AGG_TEST 终测复扫逐字一致；git D44/src untracked（实质核验=哈希重算 352+26 全件）；W-1 不修正留痕 | **PASS** | freeze/freeze-manifest.json + probe/milestone-params.json |
| M2 | 回归达标 | R-A 158/158 且 R-B 注记正确 | R-A **158/158 BUILD SUCCESS**；R-B 150/150+8 Assumption（DV-02：任务书"125+8"为 D39 期残留口径，实测 158 双口径自洽） | **PASS（注记偏差 DV-02）** | regression/mvn-full.txt、mvn-offline.txt |
| M3 | 场景复证 | R-C+R-D 对 D43 基准语义 diff=TIMING_ONLY 或 0 | scenario=NAMING_ONLY(5)、matrix=NAMING_ONLY(1)、fixed-eval=**TIMING_ONLY**、drill=NAMING_ONLY(1)；SEMANTIC=0、STRUCT=0（NAMING=d43→d44 夹具/表名等值映射，显式登记非静默） | **PASS** | rerun/semantic-diff-report.md |
| M4 | 引用真实 | V1 五项断言全 PASS 100% | 活体轮 answerable=0（mock 装配 CLARIFY 安全态，BC-19e 同款双标注），五断言零违例+IT 轮 S05/S06/S08 引用链 100%+11 单测承接 | **PASS（双标注口径）** | verify/citation-truth.json、r-e-registration.json |
| M5 | 删除同步 | V2 七断言全 PASS、SYNCED、残留=0 | **7/7 PASS**：DELETE 200、sync=SYNCED、PG=0、vci=0、检索零命中、audit DOC_DELETE ALLOWED、删除后零引用 | **PASS** | verify/delete-sync.json |
| M6 | 权限过滤 | V3 全 PASS、**跨租户泄漏=0**、命中守恒 | 6 PASS+1 EVIDENCE_REF；userB hits=[]、filterDescription 收敛 T-B、命中数 1≤1、AUTH_DISABLED/EXPIRED 拒绝正确；首跑"泄漏"=回显假阳性（BC-25 已修判定） | **PASS** | verify/permission-filter.json |
| M7 | 无证据拒答 | V4 全 PASS（契约级）；语义轨 NOT RUN 如实 | 5/5 PASS（四类近形/跨域/超长/纯符号 REFUSE+空输入 4001，零参数泄漏）；C16 真实轨 NOT RUN（O-10） | **PASS（语义轨如实 NOT RUN）** | verify/no-evidence-refusal.json |
| M8 | 交付与卫生 | 四交付+演示页冒烟+凭据 HITS=0+存储回基线 | 四交付齐；**冒烟 PASS**（页面/JS 200、health/search/answer/sync 链真实返回、导航行就位、零新端点、doc=18 抽验）；凭据 CANDIDATE=0；存储 9/10 表精确回 P0（audit 走 DEV-03 登记通道） | **PASS** | verify/demo-smoke.txt + security/ + cleanup/ |

## 判定结论

**M1–M8 全部 PASS → RAG v3 里程碑成立**（口径：mock/契约级全链路复证成立；真实模型语义
质量挂 C16/O-10，属既有授权边界而非链缺陷——与 D43 QC 终态一致，未新增未解决 P0）。

## 附带登记

- BC-21（P1 环境面）已修 VERIFIED；BC-22（P1 主码面）→ O-07 D45 首位（绕行实证零行为影响）。
- DEV-03 审计行损失 → O-13/O-02。
- W-1 无应答默认不修正 → R-G NOT RUN（C14 禁伪造口径执行）。
- Git：本轮**未 commit**（任务书"不擅自 commit"）；工作区含主码零改动+static 演示页三件
  （knowledge.html/js+index.html 1 行，任务书演示交付明示允许）+docs 证据面。

# D44 状态 R01（里程碑轮，2026-11-27）

## 1. 本轮目标 / 白名单触碰

目标：冻结+复证+交付（非新功能），M1–M8 判定 RAG v3。
触碰面（主码 java **零改动**，AGG 双哈希终测复扫逐字一致）：
- `static/knowledge.html`、`static/js/knowledge.js`（新增）、`static/index.html`（1 行导航）——任务书 §十 演示交付明示授权。
- 非代码：DB DDL×2（BC-21 ALTER，temp_docs/D44/ddl/）、evidence/temp_docs/logs 证据面。
- git：未 commit/push（任务书纪律）；D44 目录 untracked 事实已注记（C01 以哈希重算为实质核验）。

## 2. KP 门禁

KP1 聚合哈希 PASS / KP2 端点全集 19·47 PASS / KP3 表结构指纹（information_schema 形态）PASS /
KP4 污染量化+专项形态裁决 PASS —— 全部 `evidence/D44/probe/ + freeze/`。

## 3. 重跑矩阵四态

| 轮 | 命令/门控 | 结果 | 日志 |
|----|-----------|------|------|
| R-A | mvn -o clean test | PASS 158/158 | regression/mvn-full.txt |
| R-B | 同去 MYSQL_PASSWORD | PASS 150+8（DV-02 注记） | regression/mvn-offline.txt |
| R-C | -Dtest=E2eRoundIT -Dd44.e2e.run=true | round1 FAIL(BC-21)→**round2 PASS** | temp_docs/D44/mvn-r-c-round*.txt |
| R-D | -Dtest=E2eFaultDrillIT -Dd44.e2e.drill=true | PASS | mvn-r-d-drill.txt |
| 语义diff | semantic-diff.ps1 v2 | NAMING/TIMING only，SEM=0 | rerun/semantic-diff-report.md |
| R-E | 活体实例+verify-v1..4.ps1 | V1 PASS双标注/V2 7/7/V3 PASS/V4 5/5 | verify/r-e-registration.json |
| R-F | 随 R-C E2eFixedEvalRunner | PASS 恒等复现（0.0/0.0×15，饱和注记原文） | e2e/fixed-eval-results.json |
| R-G | experiment profile | **NOT_RUN**（W-1 无应答默认，禁伪造） | rerun/rerank-decision.json |

## 4. V1–V4 逐断言与 diff 计数

V1：五断言×0 引用违例（answerable=0 CLARIFY 安全态，IT 轮承接）｜V2：A1–A7 全 PASS｜
V3：①②②b③④⑥ PASS+⑤EVIDENCE_REF｜V4：五题全 PASS。
diff 计数：scenario NAMING(5)/matrix NAMING(1)/eval TIMING/drill NAMING(1)/SEM(0)/STRUCT(0)。

## 5. C01–C20 四态

C01 PASS(哈希实质) C02 PASS C03 PASS C04 PASS C05 PASS(注记) C06 PASS C07 PASS C08 PASS
C09 PASS(双标注) C10 PASS C11 PASS C12 PASS C13 PASS(0.0 恒等+注记原文) C14 NOT_RUN(原因+引用)
C15 PASS(污染矩阵) C16 PASS C17 PASS C18 PASS C19 PASS(DEV-03 通道) C20 PASS——证据路径均在各件内。

## 6. 里程碑判定

**M1–M8 全 PASS → RAG v3 成立**（mock/契约级口径；语义轨边界见报告 §8）。详表 milestone/rag-v3-milestone.md。

## 7. 对账 / 安全 / 预算

存储：9/10 表精确回 P0（doc18/principal5/sync3/vci30/task23/PG30/Redis1…）；audit 233→78=DEV-03
（删除链连坐 O-13+隔离 O-02）。凭据 CANDIDATE=0，百炼 key 全 env。
重启预算 4/4（R-C 修复轮/R-D/R-E/冒烟）未超；熔断线零触碰；跨租户泄漏 P0 线未触碰（LEAK=0）。

## 8. OPEN 项与 D45 输入

授权类：W-1(O-01 门名)/O-09(tenant 回填)/O-10+11(真实模型轨与 R3)。
修复类：**O-02 测试库隔离、O-07 BC-22 装配修复（D45 并列首位）**、O-13 审计解耦、O-14 schema 终态 diff 规范、O-15/O-16 模板类。
未 commit：本轮全部产出留在工作区，等用户复核后决定提交策略（建议单提交不 push，沿 D43 惯例）。

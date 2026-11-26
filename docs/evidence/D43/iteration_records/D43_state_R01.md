# D43 迭代状态

## D43 R01 状态（2026-11-26，P0–P8 全链单轮交付）

- **本轮目标**：生命周期写面补齐（P3）→ e2e 评测层（P4）→ mock 轮 S01–S12+P01–P10+
  固定评估（P5）→ bad-case 分类与阻断修复（P6）→ 清理对账+安全证据（P7）→ 双文档+
  三交付（P8）。
- **白名单触碰文件（git diff --stat + status 实证）**：修改 4（pom.xml +11、
  KnowledgeDocumentService +67、KnowledgeDocumentRepository +22、
  MybatisKnowledgeDocumentRepository +56）；新增 e2e 主包 9 类+LifecycleController+
  test 侧 IT×2/单测×3+application-e2e.yml+docs/evidence/D43+docs/temp_docs/D43。
  主链其余 java 文件零改动；**未 commit**。
- **KP 门禁**：KP1 装配/拒绝码 PASS（probe/e2e-capability.json）｜KP2 存储盘点 PASS
  （storage-inventory.json，含三表建表 BC-02–04）｜KP3 夹具指纹 PASS（fixture-truth.json，
  PDF→txt 退化经用户裁决 BC-05）｜KP4 参数冻结 PASS（e2e-params.json，G1–G6）
- **C01–C20 四态**：C01 PASS（158/158+125+8 注记，regression/）｜C02 PASS（e2e json）｜
  C03 PASS 带注记（S05 判题真实执行；固定评估 0.0 隔离双标注）｜C04 PASS（BC-19e 口径）｜
  C05 PASS（等价集口径）｜C06 PASS｜C07 PASS｜C08 PASS（演练轮）｜C09 PASS｜C10 PASS｜
  C11 PASS（security/access-boundary.txt）｜C12 PASS（probe）｜C13 PASS（11 单测）｜
  C14 PASS（指纹一致+饱和注记）｜C15 PASS（远程计数=0）｜**C16 NOT_RUN**（真实模型未授权，
  冻结维持）｜C17 PASS（fix-records 19 项）｜C18 PASS（cleanup-verify 全绿）｜C19 PASS
  （HITS=0）｜C20 PASS（本文件+双文档+三交付）
- **S 终态**：11 PASS｜S02 NOT_RUN｜S10 BLOCKED(round)+PASS(演练)｜零 FAIL
  **P 矩阵**：10/10，泄漏=0 **bad-case 分布**：GEN 4｜PERM 3｜EMB 2｜RETR 1｜UNCL 1（已裁决）｜
  PARSE 0｜CHUNK 0 **修复**：BC-01–05+BC-11–19 全复测 PASS（fix-records）
- **双口径回归终值**：158/158（完整）｜125+8 NOT RUN（离线）**存储对账终值**：
  doc=18、principal=5、sync/audit=0、e2e 前缀全链 0、PG vs=30、two=8、Redis db0=1
  （均=P1 冻结）**熔断与预算**：泄漏零真实触发（BC-15 假阳性复核）；Token 远低于 300 万；
  round 迭代 20 轮无同根因连续 2 次失败（G 前置纪律）
- **OPEN 项与下轮输入（D44）**：①拒绝面机器码（BC-16 遗留）②Rerank Runner 装配组合
  （BC-11）③Validator 数字 token 切分（BC-19d）④历史 30 行 tenant 回填→跨轨评估可比性
  （数据修改需授权）⑤C16 真实模型轮（需授权）⑥前段 BC-06–10 空号注记已留 fix-records §0
---

## D43 R02 状态（QC-R02 复跑质检轮，2026-11-26）

- **本轮目标**：D43 复跑扫描重大工程缺陷 → 分级整改或出质量报告。
- **白名单触碰文件（git 实证）**：修改 E2eRoundIT（+seedPrincipals 27 行）、
  E2eFaultDrillIT（+seedAdminPrincipal 20 行+Files import）、fix-records.md（§3 追加）、
  D43-01（§4 修正+注记）；新增 docs/evidence/D43/qc/D43-QC-R02_工程质量复跑检测报告.md、
  temp_docs/D43/{mvn-qc-rerun-full,mvn-qc-rerun-round-broken,mvn-qc-rerun-fixed,
  mvn-qc-final-cleanup,audit-export-before-cleanup,qc-p3-diff}.txt +
  qc-backup-*.json×3 + qc-broken-evidence-*.json×2。主码零触碰。
- **缺陷分级**：P0=0；P1=2（BC-20 已整改；QC-OBS-1 回归共享库漂移登记 D44——越界不整改）；
  P2=4；P3=1（报告 §2 全表）。
- **复跑结果**：158/158 BUILD SUCCESS｜round+drill 2/2 PASS｜三 json 与 round20 语义
  diff=0（仅计时抖动）｜测试覆盖复核：生命周期 14 单测含失败分支、分类器 11 全过。
- **存储终值**：doc=18/principal=5/sync=0/audit=0/vs=30/two=8（P1 冻结全恢复）。
- **门禁复核**：G-1~G-6 结论不变；C15 凭据复扫维持 HITS=0。
- **熔断与预算**：无新触发；QC 轮 Token 消耗低。
- **OPEN 项**：D44 输入新增首位=QC-OBS-1 测试库隔离；余 6 项沿 R01 清单。
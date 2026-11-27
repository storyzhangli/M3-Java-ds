# D44 优化清单（optimization-backlog）

编号规则：**种子 O-01~O-12 按任务书 §6.7 预分配体系原样承接**，本轮实测新增项从 O-13 起连续追加。
字段：id / 级别 / 摘要 / 证据锚点 / 影响面 / 候选方案 / 建议轮次 / 是否需授权。

## 一、种子项（任务书 §6.7，D43/D42 遗留）

| id | 级别 | 摘要 | 证据锚点 | 本轮状态 | 建议轮次 | 需授权 |
|----|------|------|----------|----------|----------|--------|
| O-01 | P1 | 实验轮门名漂移（P_EXP_RUN→D44_EXP_RUN，F05） | probe/freeze-scope.json + W-1 无应答记录 | **维持不修正**（最小权限默认）；R-G=NOT_RUN 如实登记 | 用户裁决后 | **是（W-1 待答复）** |
| O-02 | **P1** | 测试共享库隔离（QC-OBS-1） | 本轮 DEV-03 实证升级（见 O-13 关联）+ D43 观察 | 升级 P1 实证：IT 与共享 ai_db 已造成审计历史不可恢复损失 | **D45 首位** | 方案需确认（表前缀/专用 schema） |
| O-03 | P2 | 版本发布中途失败无索引补偿（QC-OBS-2） | D43 QC 报告 §2 | 未触碰（里程碑轮零改动） | D45 | 否 |
| O-04 | P2 | preClean 前缀 LIKE 口径不一致（QC-OBS-3） | D43 QC 报告 §2 | 未触碰 | D45 | 否 |
| O-05 | P2 | 报告键序不稳定（QC-OBS-4） | D43 QC 报告 §2 | 未触碰（semantic-diff 侧已用显式键序对比缓解，见 scripts/semantic-diff.ps1） | D46 | 否 |
| O-06 | P2 | 拒绝面机器码缺失（BC-16） | 本轮 V3-④ 实况复核：msg 仍为「身份凭据无效或缺失，拒绝访问：AUTH_DISABLED」码在尾缀非独立字段 | 复现确认，未修 | D45 | 否 |
| O-07 | **P1** | Rerank Runner 装配组合（BC-11→**BC-22 本轮实证升级**） | re-service.log（首启失败堆栈）+ verify-v*.txt 绕行成功 | 升级 P1：默认主配置组合 pgvector=on+rerank=off **应用无法启动**；D43 仅 IT TestConfiguration 规避未修主码；绕行面行为零影响已双标注（r-e-registration.json） | **D45 首位并列** | 否 |
| O-08 | P2 | Validator 数字 token 切分（BC-19d） | D43 fix-records | 未复发（R-C 语义 diff=0） | D46 | 否 |
| O-09 | P2 | 历史 30 行 tenant=NULL 回填（fail-closed 使 eval hitRate 恒 0） | fixed-eval-results.json（本轮 15 题 hitRate@5=mrr@5=0.0 与 D43 恒等复现，saturationNote=「未饱和」原文保留） | 复现确认，数据变更不动 | D45（**需数据修改授权**） | **是** |
| O-10 | P2 | C16 真实模型语义轨评测 | r-e-registration V1 realModelTrack=NOT_RUN | 维持 NOT RUN（禁伪造口径执行） | D45 | **是（模型调用成本）** |
| O-11 | P3 | rerank 未饱和语料重测与 R3 授权轮（D42 遗留） | D42 extract/rerank-decision.json | R3 仍 BLOCKED 口径不变 | 与 O-10 同批 | **是** |
| O-12 | P3 | EOF 换行/风格项（QC-OBS-5/6） | D43 QC 报告 | 未触碰 | 机会 | 否 |

## 二、本轮实测新增（O-13 起连续）

| id | 级别 | 摘要 | 证据锚点 | 候选方案 | 建议轮次 | 需授权 |
|----|------|------|----------|----------|----------|--------|
| **O-13** | P2 | **审计与文档生命周期解耦**：AdminAuditRepository.deleteByDocument(L43) 文档删除链连坐物理清审计，233→78 行历史不可恢复，审计不可变性破坏（DEV-03） | cleanup/cleanup-reconciliation.json + failure-case-book §2-DEV-03 | 删除文档保留审计（软删标记/归档表二选一）；IT 清理钩子只删本轮 trace 行 | D45（与 O-02 同批设计） | 否 |
| **O-14** | P2 | **跨迭代 DDL 克隆治理**：BC-14 修复（version varchar(32)）在 D44 骨架建表时回归为 BC-21——克隆只复制初始脚本未 diff 前代终态 | ddl/bc21-version-varchar.sql + failure-case-book §2-BC-21 | 迭代建表规程加「information_schema 终态 diff」步骤；或引入 migration 台账（表结构演进链每代表） | D45 规范落地 | 否 |
| **O-15** | P3 | **专项脚本自检清单**入模板：题集必须 key sentence 级原文；泄漏判定必须 hits 级（禁响应全文正则）；COUNT 必须数字行提取；HTTP 封装必须空体分支；hashtable 禁内嵌多行双引号 SQL | failure-case-book §2-BC-23/24/25（三条同构测试侧缺陷） | 模板附 verify-script-checklist.md；O-12 自动化同批 | D45 模板 | 否 |
| **O-16** | P3 | 固定评估题集扩容与阈值标定：15 题过薄且 relevant-score 0.15 与 mock embedding 分布错配（BCASE-07 遗留）；建议 ≥40 题+分层难度，配合 O-10 真实轨双跑 | fixed-eval-results.json（15 条） | 题集编制规范补「难度分层」节 | 与 O-10 联动 | 否 |

## 三、纪律备注

- 本轮无新增 P0 阻断。BC-21（P1 环境面）已修 VERIFIED；BC-22（P1 主码面）受里程碑轮
  主码零改动约束顺延 O-07，绕行面行为零影响已实证——两种装配（IT TestConfiguration / 活体绕行参数）
  均有日志证据，**不构成带病宣布**。
- 需用户授权项汇总：**O-01（W-1 门名）、O-09（数据回填）、O-10（模型成本）、O-11（R3 授权轮）**。
- 与 failure-case-book 互指：BC-22→O-07；BC-21→O-14；DEV-03→O-13+O-02；BC-23/24/25→O-15。

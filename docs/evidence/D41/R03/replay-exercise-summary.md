# D41 R03 — 演练复跑证据汇总（HTTP 域回归 + 夹具 113 格对账实证）

> R02 未解决项①闭环：R02 改动全在库内算法/服务层，91 项单测覆盖语义面，但未复跑 HTTP 域演练，
> 故当时不得宣称夹具对账 0 差异（推断非实证）。本轮复跑 run-d41-table-r01.ps1 全链，实证化。

## 1. 执行信息

- 脚本：`docs/evidence/D41/scripts/run-d41-table-r01.ps1`（零修改复用）
- 批次：`d41t-r01-20261124-220506`（credentials-d41t-r01-20261124-220506.json / docmap.json 实文件核对）
- OutDir：`docs/evidence/D41/R03/r01-exercise-replay/`（独立目录，未覆盖 R01 证据 `r01-exercise/`）
- 代码基线：工作树 = bc83b7e + R03 审计修复（TableController，未提交前状态）；应用由脚本自编译自启（AI_MODE=mock、access.enabled=true、9090）
- 结果：**9/9 场景 PASS，脚本 exit=0**

## 2. 九场景判定（final-verdict-r01.json 同口径转录）

| 场景 | 判定 | 关键实证 |
|---|---|---|
| S1 夹具入库（9 文件 register+import） | PASS | 9/9 code=200，docmap 落盘 |
| **S2 逐格对账** | **PASS** | **cellsCompared=113，diffCount=0**（s2-reconcile-report.json）——104 fixture + 9 构成，R02 列锚支持度过滤等算法改动对夹具零回归，由推断升格为实证 |
| S3 A1–A5 异常矩阵 | PASS | 含 A4b END NOTE 界外（rowCount 合计 ≤8 在列锚过滤后仍成立）、docx DEGRADED 恰 1 |
| S4 C08 双轨 | PASS | 重抽后 linkedCount≥1、LINKAGE_MISSING=0、行文本 ⊆ chunk |
| S5 重抽幂等+审计 | PASS | 双次 extract 表计数不变；audit 出现 TABLE_EXTRACT 行（本脚本不校验 result 值，PARSE_FAILED 语义由 TableControllerAuditTest 4 例锁定） |
| S6 C09 权限 | PASS | USER 同域读 200 / 跨部门拒 / USER 重抽拒 / 无 token 拒 |
| S7 TABLE_ORPHAN | PASS | dryRun=true 注入行检出，演示行即删 |
| S8 删除闭环绑定 | PASS | 向量删除事务清表 te=0/tr=0 |
| S9 清理+POST 指纹 | PASS | te=0 tr=0 principal=4 audit=maxPre doc=0 pg=30 全中 |

## 3. 指纹纪律

- **R03 PRE == R03 POST**：counts 12 项 + fixtureResidual 8 项全字段一致（本节独立比对，非脚本自断言）→ 本演练对环境零净变化。
- 与 R01 历史 POST 唯一差项 `d41_document_sync 9→10`：归因 = 2026-11-24 17:03:17 行 `e44fd7118924…(uuid)` DELETE/SYNCED，
  创建时刻在 R01 POST 指纹（17:00:27）之后，属 R01 演练 S8 异步删除落账与 S9 清理的竞态残留（R01 遗留特性，非 R03 产生；
  R03 PRE 已含该行）。按项目规范未删改该外部数据行，仅登记。
- 基线核对：kd=10/page=27/content=12/chunk=27/task=20/audit=0/prin=4/te=0/row=0/vector=30/two=8 与 KEY 基线一致；sync=10（基线 9 + 上述残留 1）。

## 4. 证据文件索引

docmap.json / s1-import.json / s1-tables-snapshot.json / s2-console.txt / **s2-reconcile-report.json** /
s3-anomaly-matrix.json / s4-dualtrack.json / s5-idempotent-audit.json / s6-permission.json /
s7-table-orphan.json / s8-delete-binding.json / s9-cleanup-post-fingerprint.json /
fingerprint-PRE.json / fingerprint-POST.json / final-verdict-r01.json / instances/app.out.log / instances/app.err.log

## 5. 诚实边界

- 本节属**受控夹具**对账实证，不得表述为真实手册验证；真实手册面证据见 `../public-manual-second/`（SICK）与 R02 `../R02/public-manual/`（Danfoss）。
- a2/a4 等异常夹具在列锚 ≥2 行支持过滤后旗标与行数仍满足 S3 断言（PASS 事实）；夹具未覆盖的场景（多层合并表头、双栏正文、大表行断裂）见 R03 报告"仍未解决"。

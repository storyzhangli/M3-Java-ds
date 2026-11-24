# D41 R01 证据总索引（docs/evidence/D41）

任务书：`docs/temp_docs/D41/D41-项目迭代任务书Prompt-设备手册中的表格与结构化信息.md`
状态文件：`iteration_records/D41_state_R01.md`

| 目录 | 内容 | 对应任务书交付物 |
|---|---|---|
| probe\ | KP1–KP4 探测输出、table-params.json 冻结参数、指纹 PRE、D13 diff 探针（probe-diff-out 见 d41-diff-probe-round.txt） | #3 |
| test\ | 编译 P1 round1–4、D40 迁移测试 54/54、table 单测 round1–3（终版 71/71） | #1 验证 |
| ddl\ | d41-table-ddl.sql 执行记录（两遍幂等 affected=0） | #2 |
| r01-exercise\ | P4 演练第 7 轮九场景原始证据：console.log、final-verdict-r01.json（9×PASS）、docmap.json（清理权威 UUID 清单）、s1–s9 各场景 json、应用实例日志 | #4–#8 原始件 |
| extract\ | rules.md 解析规则+错误清单、reconciliation-log.json 逐格对账、samples\s1-tables-snapshot.json 抽取 JSON 对照（源表原文=temp_docs\D41\fixtures） | #4/#5/#6 |
| exceptions\ | s3-anomaly-matrix.json（A1–A5 五类异常 9 项 checks 全 true） | #7 |
| linkage\ | s4-dualtrack.json（双轨关联 linkedCount=1/missingCount=0 + 行样本 recordHash） | #7 |
| security\ | s6-permission.json（D39 权限 5 项 PASS）、s5-idempotent-audit.json（重抽幂等 + TABLE_EXTRACT 审计 2 行 ALLOWED） | #7 |
| sync\ | s7-table-orphan.json（TABLE_ORPHAN 检出）、s8-delete-binding.json（删除闭环残留 0 + SYNCED） | #7（D40-R03 绑定） |
| cleanup\ | s9-cleanup-post-fingerprint.json（FINGERPRINT_OK）、docmap.json | #8 |
| capability\ | kp2-mysql/pg-inventory、table-params.json（能力边界与退化：DOCX_TABLE_NOT_STRUCTURED 实证、OCR 无引擎） | #3/#8 |
| regression\ | on\/off\ 双态 D37 回归（各 diff=0、60 行×20 质量列 PASS）、off-app\ OFF 态应用级实证（8/8 PASS）、d37-rerun 原始产物、console 日志 | #8 |
| scripts\ | run-fingerprint-d41.ps1、run-d41-table-r01.ps1（演练终版）、run-d41-table-off-verify.ps1（OFF 实证终版） | #2 |
| git\ | pre-commit-info.md（提交前 HEAD 与暂存核验） | #11/C20 |

原始件与归档件关系：exceptions/linkage/security/sync/cleanup/extract 目录内文件为 r01-exercise 同名件**原样副本**（字节一致），r01-exercise 保留演练时原始落盘位置不改。夹具 PDF/txt/docx 原文与生成器在 `docs/temp_docs/D41/fixtures\`（git 需 add -f）。

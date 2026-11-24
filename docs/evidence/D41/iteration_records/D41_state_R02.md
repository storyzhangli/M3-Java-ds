# D41 迭代状态 — R02（2026-11-24）

- 基线：R01 `3ac8c6129f43269caef7918f8cfb02adbd7e8b74`（master，起点工作区 clean 已核）
- 范围：整改书五项定向修复（R02-01…05），非重做；R01 成果全保留
- 结论：**五项全部完成**；`mvn -o -B clean test` 91/91 PASS、Skipped=0、exit 0

## 五项状态

| 项 | 状态 | 关键证据 |
| ---- | ---- | ---- |
| R02-01 真实手册解析验证 | DONE（真实公开手册，非夹具） | public-manual/ 六件+探针；31 格人工对照，零静默丢失；1 格源文件字体噪声如实标注 |
| R02-02 PDF 列分桶静默合并 | DONE | assignColumns 三阶段+锚支持度过滤+EXTRA_CELL；10/10（R01 6 项零回归） |
| R02-03 TXT/MD 异常行无痕丢弃 | DONE | switchProbe+异常组表 10000 段+EXPECTED_COLS；7/7 |
| R02-04 重抽取原子替换+三态+事务 | DONE | ParseOutcome+先删后插同事务；真实 MySQL 8/8（回滚 record_hash 复原）+fake 5/5 |
| R02-05 Maven 真实执行证据 | DONE | test/ 四件；R01 .log 缺失根因（*.log ignore）已实证并以 add -f 整改 |

## 回归与门禁事实

- D40 迁移测试（vectorindex 21 + vectorstore 3）PASS；同步域 30 PASS；Html/UnitTyping PASS。
- D37 冻结数据集/基线 SHA 未触碰；共享表零写入（d41r02- 前缀+清理+基线指纹复原断言，日志 `D41R02-IT cleanup tables=3 rows=5`）。
- 数据库当前表格域计数已复原基线（0/0）。

## 已知保留面（不掩饰）

1. **夹具 113 格对账与 HTTP 九场景演练本轮 NOT RUN**（算法改动属库内，单元+集成+真实手册三层已覆盖；HTTP 域统一留 R03 复跑，本轮不宣称其对账结论）。
2. auditIfPresent 对 PARSE_FAILED 记 ALLOWED 的审计语义瑕疵（D41-02 §9.1，留 R03）。
3. 真实手册多层表头列名合并（N315/N355）、双栏正文误判候选表：源文档版式复杂度所致，R02 目标是"异常可见+原文保留"（已达成），全量结构化属 D42+ 能力。
4. 685→885 类源 PDF 文本层损坏无法在禁 OCR 约束下修复（provenance §4）。

## 自评（10 分制，对齐整改书 §十）

**8.0/10**。达成：五项闭环、真实手册暴露并修复 1 个产品级 NPE、真实 MySQL 事务实证（非 Mock）、断言零弱化、失败史如实入库、版权纪律执行。扣分：HTTP 域回归与夹具对账顺延 R03（-1.0）、审计瑕疵与多层表头边界遗留（-0.5）、样本单一（仅 1 份手册，-0.5）。

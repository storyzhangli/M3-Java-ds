# D41 R03 — 未解决问题复跑（整改轮三）

- 输入：R02 报告"6. 未解决问题"四项（state_R02 / bc83b7e）
- 基线：bc83b7eac56a37f23e04bfeec0f5a9fa253a7845（R02）；本轮提交见 `git/`
- 模式：完全访问会话；AI_MODE=mock；密钥仅环境变量（MYSQL_PASSWORD 会话注入未落盘，PG_VECTOR_PASSWORD User 级）；禁新增框架/禁 OCR 约束延续

## 四项处置结论

| # | R02 未解决项 | R03 处置 | 判定 |
|---|---|---|---|
| ① | HTTP 域回归与夹具 113 格对账未复跑（推断非实证） | 复跑 run-d41-table-r01.ps1 全链（新批次 d41t-r01-20261124-220506，独立 OutDir） | **闭环**：9/9 PASS；S2 cellsCompared=113 diffCount=0；PRE==POST 全字段指纹一致 → 零回归由推断升格实证。见 `replay-exercise-summary.md` |
| ② | auditIfPresent 对 PARSE_FAILED 记 ALLOWED 语义瑕疵 | TableController 修复（PARSE_FAILED→FAILED+诚实 reason）+ TableControllerAuditTest 4 例 | **闭环**：单测 4/4；全量 95/95 Skipped=0；HTTP 契约零变更。见 `audit-fix/audit-semantics-fix.md` |
| ③ | 单一样本（1 份 Danfoss 手册） | 第二厂商 SICK 官方直链手册（8028100.1JAW，86 页，SHA `0048F317…8267F`）探针解析 + Read 原页人工对照 21 格（≥10 格要求满足） | **闭环 + 新发现登记**：page86 16 格值全对（多栏混排有旗标零静默）；page78 表 30 仅 2/11 行、类型/描述粘连列、首行升格 header → 定性为 R01 检测层既有能力边界（R02 改动面 git show 取证未涉表识别），列入 D42+。见 `public-manual-second/` |
| ④ | 多层合并表头 / 双栏正文误判（能力边界） | 按任务书约束不修复，SICK 交叉验证将其扩展为多栏组归属错乱实证（COLUMN_MISALIGNMENT 旗标可见），维持 D42+ | **如实保留**（R03 不解决） |

## 中间过程（不粉饰）

1. 首轮后台 `mvn clean test` 未注入 MYSQL_PASSWORD → IT 类 Assumptions 整类跳过（Tests run 87, IT 计 0）——非失败但不可作正式证据；正式轮注入后会话重跑 95/95。
2. 演练第一次启动命令被一段多余的路径探测行（mvn 路径拼写错误）拖停，未产生任何演练副作用；剔除后重启成功。
3. SICK 探针 SICKSymbols1 符号字体 No Unicode mapping WARN（c49–c56）——与 Danfoss ArialMT 同类源层缺陷；对应脚注标记数字非表格值，未影响 21 格对照。
4. 指纹 sync 9→10 差项归因 R01 时代（17:03:17）异步删除落账与清理竞态残留行，非 R03 产生；R03 PRE==POST 成立；外部数据未删改。

## 计数与证据账

- 测试：95 = 71（R01）+ 20（R02 净增）+ 4（R03 审计）。`test/maven-test-run-r03.txt`（21 689 B，exit 0）+ `test/surefire-summary-r03.txt`（13 类）。
- 演练：`r01-exercise-replay/` 15 件 + instances 日志（未覆盖 R01 同名证据）。
- 手册：`public-manual-second/` source-metadata.json / manual-sha256.txt / sick-probe-summary.txt / source-vs-extracted-sick.md / sick/manual-tables.tsv（50 088 B）；PDF 本体在 gitignore 目录。
- NOT RUN（如实）：夹具 HTTP 对账的 PARSE_FAILED 端到端真实触发（演练 9 夹具无失败解析样本，单测覆盖语义）；SICK 大表断裂根因代码级定位（范围外）；第二厂商 TXT/MD 面交叉（R03 任务书仅要求手册 PDF ≥10 格）。

## 验证口径声明

已执行并通过：mvn 全量 95/95、演练 9/9+exit 0、S2 113/0 报告复核、指纹 PRE/POST 独立逐字段比对、SICK 探针 exit 0+21 格对照、审计单测 4/4、git show 改动面取证。
已执行但未计入修复：SICK 表 30 漏检现象登记（不修）。
未执行：见 NOT RUN。

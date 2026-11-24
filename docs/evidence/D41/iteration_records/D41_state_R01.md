# D41_state_R01 —— 设备手册中的表格与结构化信息（two-lp 仓）

- 迭代/切片：D41 / R01；骨架基线 commit：**e333e35f4bf674c0e1faae60a0b84167d8376579**「D41 项目基础骨架」（差异记录：任务书 §1.1/D9 假设骨架未提交、HEAD=6385d89；实际执行前骨架已提交，P0 按复核既有提交处理）；本切片交付 commit：本次提交（`git log --oneline -1` 即 D41 迭代提交，提交信息「D41 项目迭代R01 设备手册表格与结构化信息」）
- 技术基线：Spring Boot 3.5.16 / JDK 21 / Spring AI 1.1.8 / PDFBox 3.0.7（父 POM pin，实证 Loader.loadPDF 无 PDDocument.load）/ MyBatis-Plus 3.5.17 / PG 18.7 + pgvector 0.8.7 / MySQL 8；jsoup 1.22.2、tika 3.3.1（dependency 实读）
- 冻结参数：pdfboxVersion=3.0.7 baselineSha=CD875F83611F356537790CE856797DA8057A5DCF735D82B63508A80CE1369C3F（IN_MEMORY_LOCAL\20261120-195125）datasetHash=896BCEAA1EDE… denyCode=AUTH_FORBIDDEN（响应面经 handler 归一 4001+msg）sentinel=ALL legacyCounts={vector_store:30, two_vector_store:8} extractAlgorithm=PDFBox 坐标聚类（行容差 fontSize×0.5 / 列间隙 24pt / pendingRow 半间隙 12pt）cellSchema=cells_json[{h,v,t,u}] linkageRule=pageNumber+规范化包含匹配 degradationCodes={DOCX_TABLE_NOT_STRUCTURED, IMAGE_TABLE_NOT_PARSED, COLUMN_MISALIGNMENT, MERGED_CELL, LOW_CONFIDENCE, LINKAGE_AMBIGUOUS, LINKAGE_MISSING} fixtureTruth=temp_docs\D41\fixtures\truth\d41-fixture-truth.json（104 cells，D13 后重生成版）全文见 evidence\D41\probe\table-params.json
- 偏差裁决：D1 独立 knowledge.table 分支不改页文本 / D2 PDFBox 坐标聚类+默认顺序规范化 / D3 docx Tika 结构退化+HTML Jsoup 启用 / D4 OCR 无引擎仅接口降级 / D5 双轨页码+包含匹配 / D6 权限继承三键+审计 TABLE_EXTRACT / D7 同步复用 DELETE 闭环+UPDATE_CLEANUP+R03 租约+TABLE_ORPHAN / D8 受控构造夹具坐标真值 / D9 骨架先提交+D40 7 测试迁移 / D10 类型化+单位同记录+null 占位+version String 契约（物理 int 列） / **D11（执行期）TWO_EMBEDDING_ENABLED=false 实证启动崩溃（TwoPgVectorConfiguration→EmbeddingProperties.requireValid:72），改默认 true+无 Key+仅 LOCAL 主轨=零远程等效，脚本注释固化** / D12 编号未启用 / **D13（执行期）R6 对账检出两组 diffs：pendingRow 表头前置吸收 + DIMENSION_SUFFIX 尺寸否决 + 夹具 Merged Cells 文本修正（物理压锚点粘连），fixtures+truth 重生成，非静默放行合规修复**

## 门禁与用例落表

| 编号 | 状态 | 证据路径 | 备注 |
|---|---|---|---|
| KP1 | PASS | probe\table-params.json | 依赖实证 pdfbox3.0.7/jsoup1.22.2/tika3.3.1；HTML 分支裁决启用 |
| KP2 | PASS | probe\kp2-*.txt（4 件） | 存储盘点/DDL 检查/DOCX_TABLE_NOT_STRUCTURED 实证/OCR 无引擎 |
| KP3 | PASS | temp_docs\D41\fixtures\（9 夹具+truth 104 cells） | D13 后全量重生成版 |
| KP4 | PASS | probe\table-params.json | 算法/schema/关联/退化码冻结 |
| C01 | PASS | test\compile-test.txt + test\test-p1-table-round3-d13fix.log | 迁移 54/54 + 合计 71/71（69+D13 回归锁 2） |
| C02 | PASS | probe\ + 本文件骨架差异记录 | 演练共 7 轮（R1–R7）留痕 scripts |
| C03 | PASS | ddl\d41-table-ddl-run.txt | 两遍幂等 affected=0；预建表适配禁 ALTER |
| C04 | PASS | probe\kp2-{mysql,pg}-inventory.txt + fingerprint-PRE.json | 30/8 恒等 |
| C05 | PASS | regression\{on,off}\D37-quality-columns-diff.txt | 双态 diff=0、60 行×20 质量列 PASS；new-run-sha on=407606590E55… off=E9B43CE756AE… |
| C05b | PASS | regression\off-app\（verdict 8/8） | OFF 应用级实证第三轮全绿；首轮 $scripts 路径 bug 失真已修复（教训入档 D41-01 §10-13） |
| C06/C07 | PASS | extract\reconciliation-log.json | 任务书投影分表文件以统一对账件承载（composition 字段口径映射） |
| C08 | PASS | linkage\s4-dualtrack.json | linkedCount=1 missingCount=0 recordHash 留痕 |
| C09 | PASS | security\s6-permission.json + s5-idempotent-audit.json | msg 文本断言（reason 码不外泄实证）；审计 2 行 ALLOWED |
| C10 | PASS | sync\s7-table-orphan.json + s8-delete-binding.json | 残留 0+SYNCED；COLLATE 修复实证 |
| C11–C15 | PASS | exceptions\s3-anomaly-matrix.json | A1–A5 9 checks 全 true（聚合件承载投影单文件） |
| C16 | PASS | extract\rules.md + D41-02 §5/§7 | |
| C17 | PASS | extract\reconciliation-log.json | cellsCompared=113 diffs=0 |
| C18 | PASS | capability\kp2-* | docx 退化实证/HTML 裁决/POI 不引入（H3 未触发） |
| C19 | PASS | cleanup\s9-cleanup-post-fingerprint.json + docmap.json | FIXTURE_REMAINING=0；**按 UUID 清单清理（source 落库空串禁作判据）**；指纹 FINGERPRINT_OK |
| C20 | PASS | cost\budget-evidence.json + git\pre-commit-info.md + proj_docs 双件 + 本文件 | 不 push；temp_docs add -f、credentials JSON 不提交 |
| A1–A5 | PASS | exceptions\s3-anomaly-matrix.json | 触发+处置证据齐全 |
| S1–S9 | PASS | r01-exercise\final-verdict-r01.json + console.log | 演练第 7 轮九场景全 PASS exit=0 |

## 分级结论
L1=PASS（编译/测试继承 71/71） L2=PASS（DDL/夹具/端点契约） L3=PASS（对账 113/0、双轨、A1–A5、权限同步绑定、双态回归 diff=0） L4=PASS（门禁/审计/卫生/预算/清理复原基线） L5=NOT RUN（单机）

## 抽取与对账结论
- 故障码表/参数表逐格=真值（113 compared diffs=0）；合并格 MERGED_CELL 值归首列其余 null；跨页续表重复表头未入数据行；空格 null 占位不串列；图片表格 IMAGE_TABLE_NOT_PARSED 降级零崩溃；docx DOCX_TABLE_NOT_STRUCTURED 诚实退化。双轨关联命中 1/零命中 0；单位与数值同记录，尺寸记号 string/null（D13）。

## 成本实耗
DeepSeek calls=0 tokens=0（AI_MODE=mock）；百炼 calls=0（**D11 等效：默认 enabled=true+无 Key+仅 LOCAL 主轨**，two_vector_store=8 指纹恒等）；OCR calls=0（无引擎）；夹具清理 FIXTURE_REMAINING=0；计数指纹恢复基线（30/8；MySQL 域 kd=10/page=27/content=12/chunk=27/task=20/sync=9/audit=0/prin=4/**te=0/tr=0**）。

## 诚实声明
- **已执行并通过**：mvn 编译与 71/71 单测（含 D40 迁移 54/54）、DDL 两遍幂等、九场景应用演练（HTTP+DB 真实链路，IN_MEMORY_LOCAL/LOCAL 主轨）、逐格对账 113/0、D37 双态回归 diff=0、OFF 应用级 8/8、指纹 PRE/POST、清理复原。
- **已执行但失败**：P4 演练 R1–R6 各轮首轮断言失败（根因与修复逐项入 D41-01 §10，R7 复跑全绿）；OFF 验证脚本首轮因 $scripts 路径 bug 部分失真（第三轮全绿）；R5 曾发现 R4 清理假成功（source 空串），已按 UUID 权威清单人工复原。
- **尚未执行**：L5 跨机复现；真实模型轨（DeepSeek/百炼 Key）端到端——本轮零远程为设计约束；surefire 之外的运行时长稳测试。
- **N/A 及原因**：docx 表格结构化（Tika 不保留结构，退化标注替代）；真实 OCR（无引擎，IMAGE_TABLE_NOT_PARSED 替代）；复杂嵌套表/斜线表头（夹具范围外，D42 候选）。
- **本轮修改的 D13 三件（PdfTableExtractor/UnitTyping/夹具重生成）+ 2 回归锁测试**属任务书白名单外**实现缺陷修复**（新包内），已在此显式标注并全链复验。

## 遗留与 D42 候选
docx 表格 POI/Tika-structured 接线评估 / 真实 OCR 引擎（tesseract 或云服务）引入评估（须过 H3 熔断请示）/ 复杂嵌套表与斜线表头还原 / knowledge_document.source 落库空串缺陷（register 契约面，白名单外仅记录）/ D40 R03 遗留：VectorIndexController L130/L131 审计 version 槽 1366（非阻断）/ enforce-admin 启动断言、E3 真实新版本向量、真实模型轨 / 外部并发资产观察（D41-R03VERIFY 7 行、content 12 行历史孤儿、chunk 16 行 d36exp、task 17 行——只读未动）/ 跨机 L5。

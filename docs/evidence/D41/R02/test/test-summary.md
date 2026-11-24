# D41 R02 测试执行摘要（R02-05）

## 1. 执行事实

| 项 | 值 |
| ---- | ---- |
| 命令 | `mvn -o -B -f D41/D41_TASIIEM/pom.xml clean test` |
| 时间 | 2026-11-24 21:2x（本机，见 `test-execution-metadata.json`） |
| JDK / Maven | 21.0.7 LTS / Apache Maven 3.9.16 |
| 退出码 | 0，`BUILD SUCCESS` |
| 总数 | **Tests run: 91, Failures: 0, Errors: 0, Skipped: 0** |
| MySQL 集成 | `MYSQL_PASSWORD` 仅经环境变量注入；**Skipped=0 证明 8 个真实 MySQL 场景实际执行**（非 Mock、非跳过），日志含 `D41R02-IT cleanup tables=3 rows=5` 与测试内指纹复原断言 |

## 2. 分类结果（与 surefire XML 相互核对，见 surefire-summary.txt）

| 测试类 | 数 | 结果 | 归属 |
| ---- | ---- | ---- | ---- |
| PdfTableExtractorTest | 10 | PASS | R01 6 项继续通过 + **R02 新增 4** |
| TextTableExtractorTest | 7 | PASS | R02 重写（覆盖 R01 原 4 项语义 + 新 3 场景） |
| TableTextsUnitTypingTest | 4 | PASS | R01 不变 |
| HtmlTableExtractorTest | 3 | PASS | R01 不变 |
| TableRecordServiceReplaceSemanticsTest | 5 | PASS | **R02 新增（fake 调用序断言）** |
| TableRecordPersistenceIntegrationTest | 8 | PASS | **R02 新增（真实 MySQL：删旧/回滚/隔离/指纹）** |
| DocumentSyncServiceTest / SyncAssemblyBoundaryTest | 24/6 | PASS | D41 同步域回归 |
| vectorindex×3 / vectorstore×1 | 9/9/3/3 | PASS | **D40 迁移测试继续通过** |

对账：R01 基线 71 全部包含于 91；净增 20 = 4(PDF)+3(TXT)+5(语义)+8(集成)。

## 3. 中间失败史（如实保留，不伪造）

第一轮定向执行曾出现 **5 断言失败 + 1 真实手册 NPE**：

1. PDF 4 例（`independentCells…/inColumnText…/shiftedCell…/extraCellBeyond…`）：根因=单次错位起点被聚类为"伪列锚"，从未进入 surplus 判定。修复=列锚增加**行支持度过滤（≥2 行支持才成列）**（产品代码，非断言）。
2. TXT 1 例（`misalignedRowWithExtraColumnKeepsRawValue` 行号 4→3）：根因=测试 text block 无分隔行，期望值为我的笔误；按真实行号修正 fixture 语义（非降低断言）。
3. 真实手册 NPE（`List.copyOf(header)`）：空白表头桶经 `cleanCell` 返回 null。修复=空表头列 `col_i` 占位（产品代码，R02-01 真实文档暴露价值所在）。

修复后 `clean test` 全量 91/91。**全程未修改任何既有断言换取 PASS**；R01 冻结口径（D37 数据集、table-params 阈值）未动。

## 4. 未执行项（NOT RUN 清单）

- 应用层 HTTP 端到端（R01 演练脚本 run-d41-table-r01.ps1 重跑）：本轮 NOT RUN——R02 改动均在库内算法/服务层，语义由 91 项单测+集成测试覆盖；HTTP 面回归留待 R03 验收轮统一执行（如实标注，不宣称）。
- GitHub Actions：本仓库无 CI 配置，**不存在 CI 通过宣称**。

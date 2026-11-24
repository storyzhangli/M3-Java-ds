# D41 R02 整改证据索引

基线：R01 提交 `3ac8c6129f43269caef7918f8cfb02adbd7e8b74`；GitHub：https://github.com/storyzhangli/M3-Java-ds（本轮不 push）。
整改书：`E:\AI\document\learning\M3-Java-ds\docs\proj_docs\D41-R02整改.md`。

## 目录

| 路径 | 内容 | 对应整改项 |
| ---- | ---- | ---- |
| `public-manual/source-metadata.json` | 真实手册元数据（名称/厂商/型号/版本/日期/URL/SHA-256/页数/选型理由/版权处置） | R02-01 |
| `public-manual/sample-provenance.md` | 样本选定过程、溯源链、可复现命令、源文本层缺陷声明 | R02-01 |
| `public-manual/manual-sha256.txt` | SHA-256 12 427 018 字节 PDF 指纹 + 不入仓声明 | R02-01 |
| `public-manual/extract-result.json` | 411 候选表聚合统计 + 异常标志分布 + 召回优先边界声明 | R02-01 |
| `public-manual/source-vs-extracted.md` | **31 格人工逐格对照**（29 值对/1 源文件噪声/1 列序错位带标志；零静默丢弃） | R02-01/P5 |
| `public-manual/public-manual-verification.log` | 探针完整原始输出（元数据/表清单/字体告警实证） | R02-01 |
| `public-manual/manual-tables.tsv` | 411 表全量行抽取结果（逐条可复核） | R02-01 |
| `scripts/ProbeD41Manual.java` | 真实手册解析探针（只读、路径参数化、零 DB） | R02-01 |
| `pdf/column-bucket-anomaly.json` + `pdf/pdf-test-result.txt` | R02-02 修复口径与 4 新场景测试证据 | R02-02 |
| `text/misalignment-anomaly.json` + `text/text-test-result.txt` | R02-03 异常组表语义与 7 场景测试证据 | R02-03 |
| `reextract/row-count-reduction.json` | 行数减少清旧（真实 MySQL） | R02-04 |
| `reextract/table-count-reduction.json` | 表数减少/空结果/幂等清旧 | R02-04 |
| `reextract/rollback-verification.json` | 事务回滚 record_hash 复原 + 三态设计 | R02-04 |
| `reextract/isolation-verification.json` | 版本/文档隔离 + 非数字版本零触碰 | R02-04 |
| `test/maven-test-run.log` | `mvn -o -B clean test` 完整控制台日志（91/91, exit 0；**含 D41R02-IT cleanup 行**） | R02-05 |
| `test/test-summary.md` | 执行事实/分类对账/中间失败史/NOT RUN 清单 | R02-05 |
| `test/surefire-summary.txt` | Surefire XML 汇总（与日志相互核对） | R02-05 |
| `test/test-execution-metadata.json` | 命令/时间/JDK 21.0.7/Maven 3.9.16/退出码/计数 | R02-05 |
| `git/commit-info.md` | 提交信息（提交后回填，amend 入本提交） | P7 |

## 纪律声明

- 版权 PDF 未入库：`temp_docs/D41/manuals/` 已入 `.gitignore`；本目录只含元数据、哈希、解析结果与短引用。
- 未引入 Tabula/Camelot/Tesseract/POI；未做真实 OCR；D37 冻结资产与他线共享表零触碰。
- 测试数据仅 `d41r02-` 前缀，集成测试内置前缀清理 + 全库表格域指纹复原断言。
- 全程未修改既有断言换取 PASS；失败记录保留于 test-summary §3，未只提交成功摘要。
- `.log` 证据因全局 `*.log` ignore 规则以 `git add -f` 显式入库（R01 证据缺失教训的整改本体）。

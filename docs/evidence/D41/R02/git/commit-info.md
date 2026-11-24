# R02 提交信息

- Message（终值，未随 amend 改变）: `D41 R02 修复设备手册表格解析与重抽取一致性`
- 基线: `3ac8c6129f43269caef7918f8cfb02adbd7e8b74`（R01）
- 分支: master；本轮**不 push**（整改书 §九）
- SHA 链: 内容提交 `7b704ea` → 并入 README 声明的 commit-info 占位流程 amend 为 `c7ea776` → 本文件并入最后一次 amend；因"文件自含自身 SHA"不可实现，**终值以 `git log -1 --format=%H` 现场核验为准**，提交内容与消息以本仓 tree 为准。
- 文件清单: 代码 4（PdfTableExtractor/TableDegrade/TableRecordService/TextTableExtractor）+ 测试 4（Pdf/Txt 改，Semantics/Integration 新增）+ 文档 2（D41-01/02 R02 增补）+ 状态 1（D41_state_R02.md）+ 证据 docs/evidence/D41/R02/** 全套（README、public-manual 七件、pdf/text/reextract/test/scripts/git）+ .gitignore（temp_docs/D41/manuals/）
- 提交前检查:
  - `git status --short` 范围核对：仅 R01 基线上的 R02 必要文件，R01 成果零删除；
  - 禁入项零命中：temp_docs/target/*.pdf/credentials；
  - secret 扫描（sk-/Bearer/口令赋值）：唯一命中 `System.getenv("MYSQL_PASSWORD")` 为环境变量合规读取代码；
  - `git diff --cached --check` 警告保留：maven-test-run.log 尾随空格为 Maven 控制台原始输出格式，证据不修饰；
  - `.log` 证据经 `git add -f` 入库（R01 round3 日志缺失根因＝全局 `*.log` ignore，本轮整改本体）。
- 验证: `git show --stat HEAD`（应含 docs/evidence/D41/R02 全套与 8 个 src 文件）；`mvn -o -B -f D41/D41_TASIIEM/pom.xml clean test` 应复现 91/91。

# D40 R03 最终验收报告（final-acceptance）

## 1. 基本信息

- 仓库地址：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds`（two 线，模块 D40/D40_DUDACR，端口 9090）
- 基础 HEAD：`09aaad2`（D40 R02 项目整改）
- 最终 HEAD：本报告所在 R03 迭代提交（提交信息「D40 R03 修复重试租约并补齐跨库恢复验证」，
  90 files；核验命令 `git log --oneline -1`，其父提交为基础 HEAD 09aaad2；不 push）
- Git commit：提交信息「D40 R03 修复重试租约并补齐跨库恢复验证」（哈希自引用无法在提交内固化，以 git log 为准）
- 修改文件清单（主代码/配置/测试，共 9 文件）：
  - M `D40/D40_DUDACR/src/main/java/com/zl/d40/knowledge/sync/DocumentSyncRepository.java`
  - M `D40/D40_DUDACR/src/main/java/com/zl/d40/knowledge/sync/DocumentSyncService.java`
  - M `D40/D40_DUDACR/src/main/resources/application.yml`
  - M `D40/D40_DUDACR/src/test/java/com/zl/d40/knowledge/sync/DocumentSyncServiceTest.java`
  - A `D40/D40_DUDACR/src/test/java/com/zl/d40/knowledge/sync/InMemoryDocumentSyncRepository.java`
  - A `D40/D40_DUDACR/src/test/java/com/zl/d40/knowledge/sync/SyncAssemblyBoundaryTest.java`
  - A `D40/D40_DUDACR/src/test/java/com/zl/d40/knowledge/vectorindex/service/DeleteClosedLoopChainTest.java`
  - A `D40/D40_DUDACR/src/test/java/com/zl/d40/knowledge/vectorindex/service/IndexFailureRecoveryTest.java`
  - A `docs/evidence/D40/R03/`（证据、脚本、报告）
- 数据库变更：ai_db 两空表结构对齐（`database/mysql-ddl-align.sql`，执行前后 0 行复核）

## 2. 整改结果

| 编号 | 整改项 | 修改文件 | 测试证据 | 结果 |
|---|---|---|---|---|
| R03-01 | CAS 租约保护（markPending 不清除有效租约） | DocumentSyncRepository.java、DocumentSyncService.java | T-R03-01~03（24 例类）；E4 租约场景 | PASS |
| R03-02 | PENDING 租约超时恢复 | DocumentSyncRepository.findDueForRetry、DocumentSyncService.retryDue/leaseDurationNanos | T-R03-04/07/08/09；E4 02-lease-recovery | PASS |
| R03-03 | 测试仓储兼容（InMemory 缺 claimForRetry） | InMemoryDocumentSyncRepository.java（新建）、DocumentSyncServiceTest.java | PRE：pre-existing-test-run.log（12 例 3 NPE）→ POST：24/24 绿 | PASS |
| R03-04 | 真实删除调用链测试 | DeleteClosedLoopChainTest.java（新建） | T-R03-13~21，chain-test-run.log 9/9 绿 | PASS |
| R03-05 | MySQL DDL 核验 | database/mysql-ddl-align.sql | mysql-ddl-apply.log（6 语句 0 失败）、show-create/index、schema-verification.md | PASS |
| R03-06 | E1–E5 演练实体化 | scripts/run-e1-e5-r03.ps1、GenPdf.java、DbExec.java | e1-e5/summary.json 5/5 PASS、fingerprintMatch=true、run-all.log | PASS |
| R03-07 | 解析失败/部分入库测试 | IndexFailureRecoveryTest.java（新建） | T-R03-22~30，p2-test-run.log 9/9 绿 | PASS |
| R03-08 | 权限边界与 two_ 兼容 | SyncAssemblyBoundaryTest.java（新建）、application.yml | T-R03-31~36 6/6 绿；security/access-boundary-test.md | PASS |
| R03-09 | D37 冻结回归 | scripts/run-d37-regression.ps1 | regression/D37-quality-columns-diff.txt：60 行×20 质量列 diff=0 | PASS |

## 3. 测试统计

- Maven 全量（`mvn -o -f D40/D40_DUDACR/pom.xml test`，full-module-test-run.log）：
  **54 例，成功 54，失败 0，跳过 0，BUILD SUCCESS**。
- 单元测试（A 层）：39 例（DocumentSyncServiceTest 24 + IndexFailureRecoveryTest 9 + SyncAssemblyBoundaryTest 6）。
- 跨库组件组合测试（C 层组件级）：9 例（DeleteClosedLoopChainTest）。
- 既有回归：FaultInjectingVectorStoreTest 3 + VectorReconciliationServiceDriftTest 3，全绿。
- 并发测试：单元 CAS 竞争 2 例（T-R03-05/06）+ 真实 HTTP 双执行者并发认领 1 组（E4，一方 SYNCED 一方 NO_OP）+ 重复事件幂等 1 组（E4，行数=1）。
- 真实跨库/故障演练（B/C/D 层真实环境）：E1–E5 共 5 场景全 PASS（真实 MySQL+PG+HTTP 9090）。
- D37 回归：60 记录×20 质量列逐行比对 diff=0（剔除 4 耗时列）。
- 未执行项及原因：
  - 生产 Profile 完整 Spring 上下文启动级 fail-closed 演练：以组件级 SmartInitializingSingleton 断言覆盖（T-R03-33/34），启动级留作后续建议。
  - 真实 DeepSeek/百炼模型调用：本轮按锁定基线 AI_MODE=mock 离线执行，真实调用非本任务范围。

## 4. 数据库与异常演练

- DDL 部署状态：ai_db.d40_document_sync / d40_admin_audit 已对齐冻结 DDL
  （version VARCHAR、DATETIME(6)、document_id VARCHAR(128)、索引名与冻结基准一致）；
  执行前两表 0 行（AUTO_INCREMENT 历史残留不构成数据），执行后 COUNT 复核 0 行。
- 表结构核验：mysql-show-create-table.txt（PASS）；索引核验：mysql-show-index.txt（PASS）。
- E1–E5 执行结果（batch d40t-r03-20261123-234654）：
  - E1 PG 删除失败 → FAILED/PG_UNREACHABLE/attempt=1 → 重试 SYNCED，双端残留 0。
  - E2 部分写入（batch-size=1，第 2 次 add 失败）→ PARTIAL/EMBED_WRITE_FAILED → retry COMPLETED，双端数量一致。
  - E3 UPDATE_CLEANUP 遗留 FAILED → 首次恢复仍 FAILED（attempt=2，注入未恢复）→ 二次恢复 SYNCED，旧版本双端残留 0，活动指针不受扰动。
  - E4 重复删除行数=1；过期 PENDING 恢复 SYNCED；并发双 retry 仅一方收敛。
  - E5 指针漂移（POINTER）被 consistency 发现 → 正式删除+重建管线修复 → 漂移清零。
- MySQL 与 PG 双端一致性：各场景 recovery 证据含 PG count 与关联行 count；
  全批次 PRE/POST 指纹一致（vector_store=30、two_vector_store=8、MySQL 各表计数不变）。
- 测试数据清理：按 documentId 清单精确删除 MySQL 五表 + PG vector_store；
  临时 ADMIN principal 删除；cleanup.log 留档；fingerprintMatch=true。

## 5. 剩余问题

1. E3 场景构造简化：新版本可用性以 knowledge_document 指针稳定表达（v2 未构造真实向量），
   已在 e1-e5/E3/01-construct.json 注明；不影响 UPDATE_CLEANUP 恢复链路验证结论。
2. E5 修复通道：reconcile 不直改指针（设计冻结），修复走正式 DELETE+重建管线；
   证据 e1-e5/E5/02-repair.json 已记录。
3. Embedding 失败与 PG 写入失败在 Spring AI VectorStore.add 框架边界内聚合为
   EMBED_WRITE_FAILED，原始异常文本保留于 errorMessage 可追踪（T-R03-30 证明）。
4. enforce-admin 启动级断言建议后续以 ApplicationContextRunner 补充（组件级已覆盖）。
5. 本地测试弱口令（MySQL 本地账号，经环境变量注入）按项目约定非本轮修复重点，不适用于生产。
6. 无环境阻塞项：MySQL/PG/9090 端口全程可用，无 BLOCKED。

## 6. 最终结论

**PASS**

依据：P0（R03-01/02/03）修复并经 24 例状态机测试与真实 E4 并发/租约演练验证；
真实删除调用链 9 例绿；MySQL DDL 真实核验 PASS；E1–E5 真实故障注入与恢复全 PASS
且 PRE/POST 指纹一致；P2 测试 15 例绿；D37 冻结质量列 diff=0。
所有结论均由代码、Maven/Surefire 原始日志、真实数据库查询输出与 HTTP 演练证据支撑，
无伪造、无删测、无基线篡改。

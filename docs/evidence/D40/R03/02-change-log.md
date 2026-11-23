# D40 R03 变更日志（02-change-log）

基础 HEAD：`09aaad2`（D40 R02 项目整改）。仓库：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds`。
原则：最小必要修改；未触碰 D37 冻结基线、既有测试断言、无关模块与公共接口契约。

## 1. 主代码（2 文件）

### D40/D40_DUDACR/src/main/java/com/zl/d40/knowledge/sync/DocumentSyncRepository.java
- 新增 `markPendingConditional(documentId, operation, version, attemptCount, now)`：
  WHERE 自然键 AND（sync_status<>'PENDING' OR next_retry_at IS NULL OR next_retry_at<=now）；
  SET next_retry_at = CASE WHEN 已是 PENDING THEN 保留原租约 ELSE NULL END。
  有效租约中的 PENDING 行条件不命中 → 0 行更新，租约不被清除（R03-01）。
- `findDueForRetry` 扩为三参 `(maxAttempts, now, stalePendingCutoff)`：
  原 FAILED 退避到期分支保留；新增 PENDING 分支（租约过期 OR 无租约且 updated_at<=宽限截止）
  （R03-02）。

### D40/D40_DUDACR/src/main/java/com/zl/d40/knowledge/sync/DocumentSyncService.java
- `markPending` 既有行改走 `markPendingConditional`（0 行=有效租约中 → no-op）；
  新行插入语义不变；SYNCED 重新标记 attempt 清零语义保持。
- `retryOne` 增 PENDING 未过期快速拒绝，返回 `NO_OP_LEASED`。
- `retryDue` 计算 `stalePendingCutoff = now − leaseDurationNanos()`；
  新增私有 `leaseDurationNanos() = max(backoffMs*8_000_000, 5_000_000_000)`（认领租约与宽限共用）。
- 状态机语义冻结：next_retry_at FAILED=退避时间 / PENDING=执行租约 / SYNCED、EXHAUSTED=NULL。

## 2. 配置（1 文件）

### D40/D40_DUDACR/src/main/resources/application.yml
- `app.access` 下追加 `enforce-admin: ${APP_ACCESS_ENFORCE_ADMIN:false}`（R03-08）。
  默认 false 保持本地 mock 零差异；生产设 true 时 AccessGuard 缺席启动失败。

## 3. 测试（1 修改 + 4 新增）

| 文件 | 变更 | 编号 |
|---|---|---|
| src/test/.../sync/DocumentSyncServiceTest.java | 内部 fake 补 claimForRetry/markPendingConditional/新 findDueForRetry 覆写；新增 12 例 | T-R03-01~12（合计 24 例） |
| src/test/.../sync/InMemoryDocumentSyncRepository.java | 新建公共内存 CAS 替身（与生产 SQL 语义对齐，R03-03） | — |
| src/test/.../vectorindex/service/DeleteClosedLoopChainTest.java | 新建真实删除调用链组合测试 9 例 | T-R03-13~21 |
| src/test/.../vectorindex/service/IndexFailureRecoveryTest.java | 新建索引失败分类与恢复测试 9 例 | T-R03-22~30 |
| src/test/.../sync/SyncAssemblyBoundaryTest.java | 新建装配/权限边界测试 6 例 | T-R03-31~36 |

## 4. 数据库操作（不入 Git，证据落盘）

- `database/mysql-ddl-align.sql`：ai_db 两表（d40_document_sync/d40_admin_audit）旧结构
  与 Repository 字符串 version/微秒时间口径不兼容；两表 0 行（执行前后 COUNT 复核），
  仅 MODIFY/RENAME INDEX/DROP+ADD 二级索引对齐冻结 DDL（docs/evidence/D40/ddl/d40-sync-ddl.sql）。
  应用日志 `database/mysql-ddl-apply.log`（6 语句 0 失败）；最终结构
  `database/mysql-show-create-table.txt`、`mysql-show-index.txt`；核验报告
  `database/mysql-schema-verification.md`（PASS）。旧结构留档 *-before.txt。

## 5. 演练与回归脚本（证据目录内）

- `scripts/DbExec.java`：通用 JDBC 执行器（MySQL/PG，密码仅 env 注入）。
- `scripts/GenPdf.java`：演练 PDF 夹具生成器（pdfbox 3.0.7，页面级文本供切分）。
- `scripts/run-e1-e5-r03.ps1`：E1–E5 实体化演练（每场景独立 9090 实例 + 静态故障注入预设；
  批次 source=d40t-r03-* 隔离；临时 ADMIN principal 演练后删除；PRE/POST 指纹核验；
  按 documentId 清单精确清理）。R01 占位脚本 run-e1-e5-execution.ps1 保留但执行入口废弃。
- `scripts/run-d37-regression.ps1`：D37 冻结回归（SHA 门禁 + 全量 javac + 同参重跑 +
  剔除 4 耗时列后 20 质量列逐行比对；输出隔离至 regression/d37-rerun）。

## 6. 未修改项（合规声明）

- 未引入新依赖/中间件/分布式事务；未用 JVM 本地锁冒充分布式安全。
- 未修改 D37 冻结基线（20261120-195125，SHA CD875F… 核验一致）。
- 未删除/弱化既有测试；既有 FaultInjectingVectorStoreTest(3)、
  VectorReconciliationServiceDriftTest(3) 全量回归通过。
- 密钥仅环境变量注入；证据与提交不含密码/Token/真实业务数据。

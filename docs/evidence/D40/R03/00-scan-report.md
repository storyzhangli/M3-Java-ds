# D40 R03 阶段 1 扫描报告（00-scan-report）

- 扫描时间：2026-11-23（本地 UTC+8）
- 仓库：`E:\AI\project\AI-Learning-two-lp\M3-Java-ds`（GitHub: storyzhangli/M3-Java-ds）
- 分支：`master`；HEAD：`09aaad2603ad84815b16e00693cbbb058d26ee7f`（D40 R02 项目整改）
- 工作区：`git status --porcelain` 为空（干净，无未提交修改）
- 提交链：`ec86b75`(骨架) → `1164708`(R01) → `eea63ed`(gitignore) → `09aaad2`(R02, HEAD)

## 1. R02 实际修改内容（git show 09aaad2 --stat，5 文件 +129/−22）

| 文件 | 修改 |
| --- | --- |
| knowledge/sync/DocumentSyncService.java | markPending 仅 SYNCED 清零 attempt；retryOne 重写为 CAS claim + 单层失败落账 |
| knowledge/sync/DocumentSyncRepository.java | 新增 claimForRetry（UPDATE … WHERE 自然键+状态+计数+next_retry_at<=>? 三条件 CAS，SET PENDING+租约） |
| knowledge/sync/SyncAdminBoundaryConfiguration.java | 新增：app.access.enforce-admin=true 时 AccessGuard 缺席启动 fail-fast |
| knowledge/vectorindex/service/DocumentVectorIndexService.java | syncStateRequired 默认 true；deleteClosedLoop 无 syncService 且 required → IllegalStateException |
| two/TwoPgVectorConfiguration.java | two_ 手动装配显式 setSyncStateRequired(false) |

R02 提交**不含**测试适配、DDL 部署证据、演练执行证据、文档。

## 2. 现有状态机调用链（以 HEAD 实读为准）

- 首次删除：`deleteDocument/deleteByDocument → deleteClosedLoop → markPending（无条件 updateState，next_retry_at=null）→ PG 分批删除（失败 classify+markFailed+throw）→ MySQL 清理（失败 CONFIRM_MISMATCH+markFailed+throw）→ 双端确认（不齐 markFailed+throw）→ markSynced`。
- 重试：`D40SyncController/retryDue → retryOne → find → claimForRetry(CAS, SET PENDING, next_retry_at=leaseUntil) → handler.execute → deleteClosedLoop（再次 markPending）`。
- 自动调度：`SyncRetryScheduler（sync-retry.enabled=true 才装配，fixedDelay 5min）→ retryDue → findDueForRetry（仅 FAILED ∧ 非耗尽 ∧ attempt<max ∧ 到期）`。
- UPDATE_CLEANUP：`cleanupSupersededVersion`（不调 markPending；成功 markSyncedIfRowExists、失败 markFailed），handler 经 registerSyncHandlers 注册。

## 3. 逐项问题判定（是否在当前 HEAD 仍然存在）

| 编号 | 问题 | 判定 | 证据 |
| --- | --- | --- | --- |
| R03-01 | claim 租约被 deleteClosedLoop 内 markPending 清除（updateState next_retry_at=null），第二执行者可按 null 快照重新认领 | **属实** | DocumentSyncService.markPending L72-73 无条件置 null；retryOne 对 PENDING 行无租约有效性检查 |
| R03-02 | findDueForRetry 仅查 FAILED；认领后崩溃任务永久停留 PENDING（含首建 PENDING null 租约窗口） | **属实** | DocumentSyncRepository.findDueForRetry L90 `WHERE sync_status='FAILED'` |
| R03-03 | InMemorySyncRepository 未覆写 claimForRetry，super(null) → NPE | **属实（已实跑）** | `mvn -o test -Dtest=DocumentSyncServiceTest`：Tests run 12, Errors 3（retrySucceedsConvergesSynced / retryThrowsFallbackClassified / manualRetryResetsExhausted 均 NPE jdbcTemplate=null）；原始日志 `R03/test/pre-existing-test-run.log` |
| R03-04 | 无 retryOne→deleteClosedLoop 真实调用链组合测试 | **属实** | 测试目录仅 3 类：DocumentSyncServiceTest(12, stub handler)、FaultInjectingVectorStoreTest(3)、VectorReconciliationServiceDriftTest(3) |
| R03-05 | DDL 部署核验 | **表已存在，待核验** | JDBC 实查：d40_document_sync / d40_admin_audit 均存在且 0 行（另 d40_auth_principal 预存表不触碰）；尚无 SHOW CREATE TABLE/SHOW INDEX 落盘证据 |
| R03-06 | run-e1-e5-execution.ps1 为注释占位，无真实执行证据 | **属实** | 脚本全文 75 行均为注释步骤；docs/evidence/D40 下无 execution/ 或 R02 演练目录 |
| R03-07 | 解析失败/空白 Chunk/部分入库分类失败测试缺失 | **属实** | 同 R03-04 测试清单 |
| R03-08 | 权限边界与 two_ 兼容 | **部分已具备，缺测试与配置声明** | SyncAdminBoundaryConfiguration、syncStateRequired、two_ setSyncStateRequired(false) 已在 HEAD；yml 未声明 app.access.enforce-admin 键；无对应测试 |
| R03-09 | D37 冻结回归 | **待执行** | 冻结基线存在：docs/evidence/D37/baseline/v1.0/IN_MEMORY_LOCAL/20261123-205412/baseline.csv（R01 P6 重跑证据） |

## 4. 测试覆盖现状

- 3 个测试类共 18 例；当前 HEAD 实跑 DocumentSyncServiceTest：12 例中 3 例 NPE（BUILD FAILURE），其余 9 例通过。
- 无任何真实 Repository SQL 集成测试、无并发认领测试、无跨库组合测试。

## 5. 数据库部署证据现状

- MySQL `127.0.0.1:3306/ai_db`：连通 ✓；d40_document_sync=0 行、d40_admin_audit=0 行（表已建，结构核验待做）。
- PG `192.168.200.153:5432/spring_ai`：连通 ✓；vector_store=30 行、two_vector_store=8 行（与 KP2 冻结基线一致）。
- DDL 源文件：`docs/evidence/D40/ddl/d40-sync-ddl.sql`（幂等 CREATE TABLE IF NOT EXISTS）。
- 执行工具：本轮新增 `R03/scripts/DbExec.java`（JDK21 单文件 + JDBC 驱动，密码仅 env 注入）。

## 6. E1–E5 实际执行证据现状

- 无。旧脚本 `docs/evidence/D40/scripts/run-e1-e5-execution.ps1` 自述"状态：未执行"。本轮将创建 R03 独立可执行脚本并废弃旧脚本执行入口。

## 7. 本轮计划修改的文件

主代码（最小修改）：
1. `DocumentSyncService.java`：markPending 改条件更新（保留有效租约）；retryOne 增加有效租约快速拒绝；retryDue/findDueForRetry 口径扩展。
2. `DocumentSyncRepository.java`：新增 markPending 条件更新方法；findDueForRetry 扩展过期 PENDING 恢复（含 null 租约宽限窗口）。
3. `application.yml`：声明 `app.access.enforce-admin: ${APP_ACCESS_ENFORCE_ADMIN:false}`（生产 Profile 设 true）。

测试（新增/适配）：
4. `DocumentSyncServiceTest.java`：InMemorySyncRepository 实现 claimForRetry 真实 CAS + 条件 markPending；新增 T-R03-01~12。
5. 新增 `DeleteClosedLoopChainTest.java`（T-R03-13~21 真实调用链，Mockito 仅替代存储层）。
6. 新增 `IndexFailureRecoveryTest.java`（R03-07 解析/空白/部分入库分类失败与恢复）。
7. 新增 `SyncAssemblyBoundaryTest.java`（R03-08 fail-closed + two_ 兼容 + 主轨不降级）。

证据/脚本：
8. `docs/evidence/D40/R03/`（本报告、01-design、02-change-log、test/、database/、e1-e5/、regression/、security/、final-acceptance.md）。
9. `R03/scripts/run-e1-e5-r03.ps1`（实体化演练脚本）+ DbExec.java（已建）。

不改：D37 基线、预存 principal/knowledge_document 数据、two_ 表、无关模块。

## 8. 外部环境条件

| 条件 | 状态 |
| --- | --- |
| MySQL ai_db 连通 | ✓（JDBC 实查） |
| PG spring_ai 连通 | ✓（JDBC 实查） |
| 9090 端口 | ✓ 空闲（无监听，全机仅 Nacos java 进程） |
| Maven 离线构建 | ✓（3.9.16 + 本地仓库，JDK21） |
| MYSQL_PASSWORD | 会话级注入（本地测试口令，不落盘不提交） |
| PG_VECTOR_PASSWORD | ✓ 进程环境已有 |
| AI_MODE=mock 启动 | 可行（零 Key 零外网） |

无 BLOCKED 前置项；E1–E5 需拉起 9090 实例后执行。
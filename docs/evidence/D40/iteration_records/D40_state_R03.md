# D40_state_R03

- 迭代：D40/R03 重试状态机、并发安全与跨库一致性验证整改（D40_DUDACR, com.zl.d40, :9090）
- 日期：2026-11-23
- 基线：HEAD 09aaad2（R02）→ 本文件所在提交为 R03 迭代提交（不 push）
- 任务书：E:\AI\document\learning\M3-Java-ds\docs\proj_docs\D40 R03 项目整改任务书：重试状态机、并发安全与跨库一致性验证.md
- 验收：docs/evidence/D40/R03/final-acceptance.md → **PASS**（九项全 PASS）

## 阶段状态

| 阶段 | 状态 | 证据 |
|---|---|---|
| 1 扫描与问题确认 | DONE（9 项逐项判定） | R03/00-scan-report.md |
| 2 P0 修复 | DONE（租约保护+超时恢复+测试仓储兼容；24/24 绿） | R03/01-design.md、test/p0-statemachine-test-run.log |
| 3 真实删除链路测试 | DONE（T-R03-13~21，9/9 绿） | test/chain-test-run.log |
| 4 DDL 核验+E1–E5 | DONE（空表 ALTER 对齐 PASS；演练 5/5 PASS，指纹一致） | database/*、e1-e5/summary.json |
| 5 P2+权限+回归 | DONE（T-R03-22~36 15/15 绿；D37 60×20 diff=0） | test/p2-test-run.log、regression/D37-quality-columns-diff.txt |
| 6 验收与提交 | DONE（双文档+状态文件+迭代提交） | final-acceptance.md、本文件 |

## R01 OPEN 项关闭

- [x] 应用 sql/d40 两 DDL：旧结构不兼容 → 空表 ALTER 对齐（mysql-ddl-align.sql，6 语句 0 失败，前后 0 行复核）
- [x] 运行 E1–E5：R01 占位脚本执行入口废弃，R03 实体化脚本 run-e1-e5-r03.ps1 全 PASS（access=true 审计链真实启动）
- [x] 指纹 PRE/POST 回归：fingerprintMatch=true（vector_store=30、two_vector_store=8、MySQL 各表计数不变）
- [x] 测试数据清理：documentId 清单精删 + 临时 principal 删除（cleanup.log）

## 缺陷与修复记录（本轮）

1. markPending 无条件清租约 → 条件 UPDATE + CASE 保留（R03-01）。
2. findDueForRetry 仅 FAILED → 三参扩展 PENDING 租约/宽限分支（R03-02）。
3. InMemorySyncRepository 缺 claimForRetry → 新建公共内存 CAS 替身（R03-03）。
4. 演练脚本踩坑：txt 无页面级文本 4091 → PDF 夹具；PG metadata 驼峰键；attempt 解析误命中 SQL 回显行；PG 清理按 UUID 清单（批次前缀无效）。
5. 测试踩坑：Mockito when() 重桩副作用 → doX 风格；Document metadata null 守卫 → stub modelName；legacy 分支条件 syncService==null。
6. D37 冻结基线目录甄别：20261120-195125（SHA CD875F…）为冻结源；20261123-205412 为 R01 重跑目录。

## 冻结语义（R03 后）

- next_retry_at：FAILED=退避时间 / PENDING=执行租约 / SYNCED、EXHAUSTED=NULL。
- leaseDurationNanos = max(backoffMs×8, 5s)；stalePendingCutoff = now − lease。
- retryOne 返回码：NO_SUCH_SYNC_ROW | NO_OP_ALREADY_SYNCED | NO_OP_LEASED | NO_HANDLER_REGISTERED | NO_OP_CONCURRENT_CLAIM | SYNCED | FAILED | PENDING。
- 生产门禁：app.access.enforce-admin（yml 已声明，默认 false）；AccessGuard 缺席启动失败。

## OPEN 项（后续建议，非阻塞）

- [ ] enforce-admin 启动级断言（ApplicationContextRunner 完整上下文）
- [ ] E3 新版本真实向量构造（当前以指针稳定表达可用性）
- [ ] 真实模型轨（DeepSeek/百炼）端到端核验（本轮 mock 离线基线）

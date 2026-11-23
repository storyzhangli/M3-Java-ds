# D40 R03 阶段 2 设计（01-design）

## R03-01 CAS 租约保护（最小修改，无新字段、无 Boolean 参数泛滥）

`next_retry_at` 状态语义（复用现有字段，任务书 §4.3 表格）：

| 状态 | next_retry_at 含义 |
| --- | --- |
| FAILED | 下次允许自动重试的时间（指数退避） |
| PENDING | 当前执行租约到期时间（claim 写入；首建业务标记为 NULL，按 updated_at+宽限恢复） |
| SYNCED | NULL |
| RETRY_EXHAUSTED（FAILED+原因码） | NULL |

修改点：

1. `DocumentSyncRepository.markPendingConditional(...)`（新增条件更新）：
   - `WHERE 自然键 AND (sync_status<>'PENDING' OR next_retry_at IS NULL OR next_retry_at<=now)`
   - `SET sync_status='PENDING', reason_code='', attempt_count=?, next_retry_at=CASE WHEN sync_status='PENDING' THEN next_retry_at ELSE NULL END`
   - 有效租约中的 PENDING 行 → 0 行（no-op），租约与计数不被业务闭环重入清除。
2. `DocumentSyncService.markPending`：既有行改走 markPendingConditional；0 行=已被认领，跳过初始化（幂等 no-op）。attempt 语义不变（仅 SYNCED 新事件清零）。首次无行仍插入 PENDING（next_retry_at=NULL）。
3. `DocumentSyncService.retryOne`：PENDING ∧ 租约未过期 → 快速拒绝 `NO_OP_LEASED`（CAS 之外的第一道闸；CAS 仍是并发最终裁决）。

调用路径区分（不新增 Boolean 参数）：
- 首次业务操作：无行 → 插入 PENDING。
- 已存在 PENDING（他人租约中）：条件更新 0 行 → no-op。
- FAILED 普通重试：retryOne CAS claim（保留 attempt）。
- RETRY_EXHAUSTED 人工恢复：claim resetAttempt=true。
- 已被认领：NO_OP_LEASED / claim 0 行 NO_OP_CONCURRENT_CLAIM。

UPDATE_CLEANUP 排查结论：cleanupSupersededVersion 不调 markPending，无清租约问题；其失败 markFailed 写退避时间属正常落账。

## R03-02 PENDING 租约超时恢复

1. `findDueForRetry(maxAttempts, now, stalePendingCutoff)` 扩展：
   - FAILED 分支不变；
   - 新增 PENDING 分支：`attempt_count<max AND ((next_retry_at IS NOT NULL AND next_retry_at<=now) OR (next_retry_at IS NULL AND updated_at<=stalePendingCutoff))`。
   - stalePendingCutoff = now − 租约时长（与 claim 租约同公式 max(backoffMs*8, 5s)），覆盖"首建 PENDING 后、业务动作前崩溃"的 null 租约窗口；未过期正常执行不会被误判。
2. 恢复认领复用 retryOne 同一 CAS（expectedStatus=PENDING + 旧租约/null <=> 匹配），两个调度器竞争仅一个 1 行。
3. 恢复不增加也不清零 attempt（claim 不动计数；仅真实失败 markFailed +1）。
4. 幂等前提：deleteClosedLoop 与 cleanupSupersededVersion 全链幂等（PG 删除对不存在 id 不报错、MySQL 删除幂等、双端确认收敛），可安全重放；不满足幂等的操作不自动重放。
5. 崩溃双窗口：认领后业务前 → 租约到期恢复重放（幂等）；业务后落账前 → 重放经双端确认直接 markSynced 收敛。

## R03-03 测试仓储兼容

InMemorySyncRepository 覆写 claimForRetry / markPendingConditional / 新签名 findDueForRetry，逐条模拟自然键+状态+计数+null 安全比较+租约写入+重置语义；竞争失败返回 0。

## 验收测试映射

T-R03-01~06 → DocumentSyncServiceTest 新增（租约保护面）；
T-R03-07~12 → DocumentSyncServiceTest 新增（超时恢复面）；
T-R03-13~21 → DeleteClosedLoopChainTest（阶段 3）。
package com.zl.d41.knowledge.sync;

/**
 * 操作级同步状态（R01，与索引任务状态机正交共存）。
 *
 * <p>三态冻结（KP4）：PENDING（已标记/重试中）、SYNCED（双端确认通过）、
 * FAILED（任一步失败；重试耗尽以 reason_code=RETRY_EXHAUSTED 表达，不新增第四状态）。</p>
 */
public enum SyncStatus {

    /** 已标记、等待执行或确认。 */
    PENDING,

    /** 双端确认通过（PG count=0 ∧ MySQL 关联行=0 或旧版本残留归零）。 */
    SYNCED,

    /** 失败；细分原因见 reason_code，退避后由手动/自动重试收敛。 */
    FAILED
}

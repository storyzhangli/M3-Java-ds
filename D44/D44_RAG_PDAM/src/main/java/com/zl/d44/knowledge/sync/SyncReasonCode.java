package com.zl.d44.knowledge.sync;

/**
 * 同步原因码表（§3.2 KP4 冻结）。
 *
 * <p>新增六码归属删除/清理同步闭环；既有任务层原因码
 * （EMBED_WRITE_FAILED / COUNT_MISMATCH / NO_COMPLETE_SNAPSHOT /
 * STALE_VERSION_REJECTED / PARSE_FAILED）保留在索引任务与导入链，
 * 不迁移、不重名（NO_COMPLETE_SNAPSHOT 非 one 仓 NO_SNAPSHOT）。</p>
 */
public final class SyncReasonCode {

    /** PG 连接失败（运行期装饰器注入 / 坏端口等价连接丢失）。 */
    public static final String PG_UNREACHABLE = "PG_UNREACHABLE";

    /** PG 删除语句异常（非连接类）。 */
    public static final String PG_DELETE_FAILED = "PG_DELETE_FAILED";

    /** 双端确认失败：PG count≠0 或 MySQL 关联行≠0。 */
    public static final String CONFIRM_MISMATCH = "CONFIRM_MISMATCH";

    /** sync PENDING 标记事务失败。 */
    public static final String MARK_FAILED = "MARK_FAILED";

    /** 清理时关联行缺失但 PG 向量仍在（转 orphan 管线收敛）。 */
    public static final String SNAPSHOT_MISSING = "SNAPSHOT_MISSING";

    /** 重试达 maxAttempts 仍失败，待人工（ADMIN retry 时重置尝试计数）。 */
    public static final String RETRY_EXHAUSTED = "RETRY_EXHAUSTED";

    private SyncReasonCode() {
    }
}

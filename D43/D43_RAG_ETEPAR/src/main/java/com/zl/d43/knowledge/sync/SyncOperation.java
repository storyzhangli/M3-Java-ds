package com.zl.d43.knowledge.sync;

/**
 * 同步操作类型（R01 冻结，两类；与索引任务状态机正交）。
 */
public enum SyncOperation {

    /** 文档/版本删除：sync 标记 → 清向量 → 清关联 → 双端确认。 */
    DELETE,

    /** 更新后旧版本清理：cleanupSupersededVersions 失败落库，经管线重试收敛。 */
    UPDATE_CLEANUP
}

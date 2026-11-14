package com.zl.d31.knowledge.vectorindex.model;

/**
 * 索引阶段：标记任务最后一次进入的处理阶段，用于失败定位与恢复决策。
 */
public enum VectorIndexStage {

    /** 任务登记（状态行建立/更新）。 */
    REGISTER,

    /** 向量与关联写入（Embedding + PG Upsert + MySQL 关联 Upsert）。 */
    EMBED_WRITE,

    /** 写入后数量与关联核对。 */
    VERIFY,

    /** 版本切换（旧活动版本置 SUPERSEDED，当前置 COMPLETED）。 */
    SWITCH,

    /** 旧版本向量与关联清理。 */
    CLEANUP
}

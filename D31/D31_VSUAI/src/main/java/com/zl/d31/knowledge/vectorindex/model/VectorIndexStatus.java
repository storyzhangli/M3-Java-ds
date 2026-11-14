package com.zl.d31.knowledge.vectorindex.model;

/**
 * 文档版本级向量索引状态。
 *
 * <p>状态流转：PENDING -> INDEXING -> COMPLETED；写入中断为 PARTIAL（已写部分）或
 * FAILED（未写入任何数据）；旧活动版本被新版本取代后为 SUPERSEDED。</p>
 */
public enum VectorIndexStatus {

    /** 任务已登记，尚未开始写入。 */
    PENDING,

    /** 正在写入（Embedding + PG Upsert + 关联写入）。 */
    INDEXING,

    /** 已完整写入并核对通过，为当前活动版本。 */
    COMPLETED,

    /** 部分 Chunk 已写入后中断，可基于稳定 ID 幂等续作。 */
    PARTIAL,

    /** 写入失败且未留下可核对的部分数据，等待重试或人工处理。 */
    FAILED,

    /** 旧版本：已被新版本取代，等待清理或已清理。 */
    SUPERSEDED
}

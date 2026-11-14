package com.zl.d31.knowledge.vectorindex.model;

/**
 * Chunk 关联行状态（Outbox 快照状态机）。
 */
public enum ChunkRowStatus {

    /** 快照已落库，向量尚未确认写入。 */
    PENDING,

    /** 已写入 PG 并确认登记关联。 */
    INDEXED,

    /** 关联登记失败或向量写入异常（保留行以便核对与重建）。 */
    FAILED
}

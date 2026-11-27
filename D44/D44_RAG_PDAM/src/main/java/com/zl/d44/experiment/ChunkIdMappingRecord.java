package com.zl.d44.experiment;

import java.util.List;

/**
 * 逻辑片段 ID 与向量主键的映射表（覆盖本组全部入库块）。
 *
 * @param groupId 实验组编号
 * @param entries 映射条目
 */
public record ChunkIdMappingRecord(String groupId,
                                   List<Entry> entries) {

    /**
     * 单个入库块的映射条目。
     *
     * @param vectorChunkId   向量主键（UUID v5）
     * @param documentId      实验文档 ID（带前缀）
     * @param chunkIndex      入库块序号（0-based）
     * @param pageNumbers     覆盖的物理页码
     * @param logicalChunkIds 映射回的逻辑片段 ID
     */
    public record Entry(String vectorChunkId,
                        String documentId,
                        int chunkIndex,
                        List<Integer> pageNumbers,
                        List<String> logicalChunkIds) {
    }
}

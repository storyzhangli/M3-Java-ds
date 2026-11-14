package com.zl.d31.knowledge.vectorindex.dto;

import lombok.Data;

/**
 * Chunk 关联视图（不含完整向量，仅可追溯元数据与文本）。
 */
@Data
public class ChunkIndexView {

    private String chunkId;

    private String documentId;

    private String version;

    private String strategy;

    private int chunkIndex;

    private int characterCount;

    private String chunkContentHash;

    private String pageNumbers;

    private String status;

    private String content;
}

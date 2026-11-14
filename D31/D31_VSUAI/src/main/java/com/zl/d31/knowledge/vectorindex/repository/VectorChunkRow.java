package com.zl.d31.knowledge.vectorindex.repository;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * vector_chunk_index 行模型（Chunk 级稳定关联与 Outbox 快照）。
 */
@Data
public class VectorChunkRow {

    private Long id;

    private String documentId;

    private String version;

    private String strategy;

    private String chunkId;

    private int chunkIndex;

    private String content;

    private String chunkContentHash;

    private int characterCount;

    private String pageNumbers;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.zl.d31.knowledge.vectorindex.repository;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * vector_index_task 行模型（文档版本级状态）。
 */
@Data
public class VectorIndexTaskRow {

    private Long id;

    private String documentId;

    private String version;

    private String strategy;

    private String contentHash;

    private String splitVersion;

    private String vectorSpaceId;

    private int chunkCount;

    private String status;

    private String stage;

    private String errorCode;

    private String errorMessage;

    private int attemptCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

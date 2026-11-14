package com.zl.d31.knowledge.vectorindex.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档版本索引任务状态视图。
 */
@Data
public class VectorIndexTaskView {

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

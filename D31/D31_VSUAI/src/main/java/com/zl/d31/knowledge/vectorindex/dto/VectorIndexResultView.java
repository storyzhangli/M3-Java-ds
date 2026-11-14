package com.zl.d31.knowledge.vectorindex.dto;

import lombok.Data;

/**
 * 索引执行结果视图。
 */
@Data
public class VectorIndexResultView {

    private String documentId;

    private String version;

    private String strategy;

    private String status;

    private String stage;

    private int chunkCount;

    /** 本次实际写入的 Chunk 数（幂等短路时为 0）。 */
    private int processed;

    /** 是否命中重复导入幂等短路。 */
    private boolean idempotent;

    private int attemptCount;

    private String errorCode;

    private String errorMessage;
}

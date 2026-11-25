package com.zl.d42.rerank;

/** 重排层原因码（D5 裁决：独立枚举，不改 ExperimentReasonCode 12 码）。 */
public enum RerankReasonCode {
    RERANK_EMPTY_CANDIDATES,
    RERANK_CONFIG_INVALID,
    RERANK_KEY_MISSING,
    RERANK_REMOTE_DISABLED,
    RERANK_API_FAILED,
    RERANK_API_RETRYABLE_FAILED,
    RERANK_TIMEOUT,
    RERANK_RESULT_COUNT_MISMATCH,
    RERANK_ID_MISMATCH,
    RERANK_SCORE_INVALID,
    RERANK_FALLBACK_LOCAL,
    RERANK_LEDGER_WRITE_FAILED;

    public String code() {
        return name();
    }
}
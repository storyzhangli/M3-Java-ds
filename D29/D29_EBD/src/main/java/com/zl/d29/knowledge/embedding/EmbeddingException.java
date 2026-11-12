package com.zl.d29.knowledge.embedding;

import com.zl.d29.common.ResultCode;

/**
 * Stable Embedding business error.
 */
public class EmbeddingException extends RuntimeException {

    private final ResultCode resultCode;

    public EmbeddingException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public EmbeddingException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}

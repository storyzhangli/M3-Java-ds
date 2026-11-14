package com.zl.d31.knowledge.vectorindex;

import com.zl.d31.common.ResultCode;

/**
 * 向量索引业务异常：携带稳定业务码，对外由 GlobalExceptionHandler 归一为 Result。
 */
public class VectorIndexException extends RuntimeException {

    private final ResultCode resultCode;

    private final String errorCode;

    public VectorIndexException(ResultCode resultCode, String message) {
        this(resultCode, message, null, null);
    }

    public VectorIndexException(ResultCode resultCode, String message, Throwable cause) {
        this(resultCode, message, cause, null);
    }

    public VectorIndexException(ResultCode resultCode, String message, Throwable cause,
                                String errorCode) {
        super(message, cause);
        this.resultCode = resultCode;
        this.errorCode = errorCode == null ? resultCode.name() : errorCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

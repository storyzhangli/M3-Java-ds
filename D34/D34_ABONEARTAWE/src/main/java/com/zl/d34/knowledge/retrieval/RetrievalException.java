package com.zl.d34.knowledge.retrieval;

import com.zl.d34.common.ResultCode;
import com.zl.d34.knowledge.embedding.EmbeddingException;

/**
 * 检索业务异常。
 *
 * <p>继承 {@link EmbeddingException} 以复用既有全局异常处理与统一 {@code Result} 契约，
 * 同时通过 {@code errorCode} 提供稳定的机器可读细分（参数非法、过滤值非法、
 * 向量非法、检索执行失败等），便于日志与验收断言。</p>
 */
public class RetrievalException extends EmbeddingException {

    private final String errorCode;

    public RetrievalException(ResultCode resultCode, String message, String errorCode) {
        super(resultCode, message);
        this.errorCode = errorCode;
    }

    public RetrievalException(ResultCode resultCode, String message, String errorCode,
                              Throwable cause) {
        super(resultCode, message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

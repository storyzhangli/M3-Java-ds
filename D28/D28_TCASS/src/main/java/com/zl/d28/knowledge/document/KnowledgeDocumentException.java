package com.zl.d28.knowledge.document;

import com.zl.d28.common.ResultCode;

/**
 * 知识文档业务异常，携带稳定对外业务码。
 */
public class KnowledgeDocumentException extends RuntimeException {

    private final ResultCode resultCode;

    public KnowledgeDocumentException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public KnowledgeDocumentException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }
}

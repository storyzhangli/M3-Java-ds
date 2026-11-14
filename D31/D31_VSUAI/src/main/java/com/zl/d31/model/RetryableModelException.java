package com.zl.d31.model;

import org.springframework.ai.retry.TransientAiException;

import java.time.Duration;

/**
 * 已确认适合有限重试的模型调用异常。
 */
public class RetryableModelException extends TransientAiException {

    private final ModelFailureCategory category;

    private final Integer httpStatus;

    private final Duration retryAfter;

    public RetryableModelException(ModelFailureCategory category, Integer httpStatus,
                                   Duration retryAfter) {
        super("可重试的 AI 服务失败: " + category);
        this.category = category;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public ModelFailureCategory getCategory() {
        return category;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}

package com.zl.d27.model;

import org.springframework.ai.retry.NonTransientAiException;

/**
 * 明确不应重试的模型调用异常。
 */
public class NonRetryableModelException extends NonTransientAiException {

    private final ModelFailureCategory category;

    private final Integer httpStatus;

    public NonRetryableModelException(ModelFailureCategory category, Integer httpStatus) {
        super("不可重试的 AI 服务失败: " + category);
        this.category = category;
        this.httpStatus = httpStatus;
    }

    public ModelFailureCategory getCategory() {
        return category;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}

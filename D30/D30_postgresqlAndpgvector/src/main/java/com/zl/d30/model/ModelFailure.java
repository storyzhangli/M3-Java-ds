package com.zl.d30.model;

import java.time.Duration;

/**
 * 不含 Prompt、响应正文或凭据的模型调用失败摘要。
 */
public record ModelFailure(
        ModelFailureCategory category,
        Integer httpStatus,
        boolean retryable,
        Duration retryAfter
) {
}

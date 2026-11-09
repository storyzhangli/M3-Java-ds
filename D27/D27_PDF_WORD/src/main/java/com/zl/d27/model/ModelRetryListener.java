package com.zl.d27.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

/**
 * 仅记录分类、状态和尝试次数，不记录 Prompt、响应正文或凭据。
 */
@Slf4j
public class ModelRetryListener implements RetryListener {

    private final ModelFailureClassifier classifier;

    public ModelRetryListener(ModelFailureClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext context,
                                                 RetryCallback<T, E> callback,
                                                 Throwable throwable) {
        ModelFailure failure = classifier.classify(throwable);
        log.warn("模型调用失败，category={}，status={}，attempt={}，retryable={}",
                failure.category(), failure.httpStatus(), context.getRetryCount(),
                classifier.isRetryable(throwable));
    }

    @Override
    public <T, E extends Throwable> void onSuccess(RetryContext context,
                                                   RetryCallback<T, E> callback,
                                                   T result) {
        log.info("模型调用完成，attempt={}", context.getRetryCount());
    }
}

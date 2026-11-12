package com.zl.d29.model;

import com.zl.d29.config.ModelCallProperties;
import com.zl.d29.model.ModelFailureClassifier;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 有上限的指数退避，支持 Retry-After 和请求级抖动；不保存跨请求状态。
 */
public class ModelRetryBackOffPolicy implements BackOffPolicy {

    private final ModelFailureClassifier classifier;

    private final ModelCallProperties properties;

    public ModelRetryBackOffPolicy(ModelFailureClassifier classifier,
                                   ModelCallProperties properties) {
        this.classifier = classifier;
        this.properties = properties;
    }

    @Override
    public BackOffContext start(RetryContext context) {
        return new RetryBackOffContext(context);
    }

    @Override
    public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
        RetryContext retryContext = ((RetryBackOffContext) backOffContext).retryContext;
        int failedAttempt = Math.max(1, retryContext.getRetryCount());
        double multiplier = Math.pow(2, Math.max(0, failedAttempt - 1));
        long baseMillis = Math.max(1,
                Math.round(properties.getInitialBackoff().toMillis() * multiplier));
        long jitterRange = Math.round(baseMillis * properties.getJitterFactor());
        if (jitterRange > 0) {
            baseMillis += ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        }
        Duration retryAfter = classifier.retryAfter(retryContext.getLastThrowable());
        long delayMillis = retryAfter == null
                ? baseMillis : Math.max(baseMillis, retryAfter.toMillis());
        delayMillis = Math.max(1, Math.min(delayMillis, properties.getMaxBackoff().toMillis()));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BackOffInterruptedException("模型重试退避被中断", ex);
        }
    }

    private static final class RetryBackOffContext implements BackOffContext {

        private final RetryContext retryContext;

        private RetryBackOffContext(RetryContext retryContext) {
            this.retryContext = retryContext;
        }
    }
}

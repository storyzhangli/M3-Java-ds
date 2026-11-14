package com.zl.d31.model;

import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * 在尝试次数上限之上增加 分类判断。
 */
public class ModelAttemptRetryPolicy extends SimpleRetryPolicy {

    private final ModelFailureClassifier classifier;

    public ModelAttemptRetryPolicy(int maxAttempts, ModelFailureClassifier classifier) {
        super(maxAttempts);
        this.classifier = classifier;
    }

    @Override
    public boolean canRetry(RetryContext context) {
        Throwable throwable = context.getLastThrowable();
        if (throwable != null && !classifier.isRetryable(throwable)) {
            return false;
        }
        return super.canRetry(context);
    }
}

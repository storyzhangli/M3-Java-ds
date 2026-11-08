package com.zl.d26.model;

import com.zl.d26.config.ModelCallConfiguration;
import com.zl.d26.config.ModelCallProperties;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRetryPolicyTest {

    @Test
    void retryableFailureShouldStopAtConfiguredMaximumAndThenSucceed() {
        RetryTemplate template = template();
        AtomicInteger calls = new AtomicInteger();
        String result = template.execute(context -> {
            if (calls.incrementAndGet() == 1) {
                throw new RetryableModelException(
                        ModelFailureCategory.UPSTREAM_UNAVAILABLE, 503, null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
    }

    @Test
    void nonRetryableAndReadTimeoutShouldExecuteOnlyOnce() {
        RetryTemplate template = template();

        AtomicInteger authCalls = new AtomicInteger();
        assertThatThrownBy(() -> template.execute(context -> {
            authCalls.incrementAndGet();
            throw new NonRetryableModelException(
                    ModelFailureCategory.AUTHENTICATION_FAILED, 401);
        })).isInstanceOf(NonRetryableModelException.class);
        assertThat(authCalls).hasValue(1);

        AtomicInteger readCalls = new AtomicInteger();
        assertThatThrownBy(() -> template.execute(context -> {
            readCalls.incrementAndGet();
            throw new org.springframework.web.client.ResourceAccessException(
                    "read", new java.net.SocketTimeoutException("timed out"));
        })).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        assertThat(readCalls).hasValue(1);
    }

    @Test
    void retryAfterLargerThanBackoffBudgetShouldStopImmediately() {
        RetryTemplate template = template();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> template.execute(context -> {
            calls.incrementAndGet();
            throw new RetryableModelException(
                    ModelFailureCategory.RATE_LIMITED, 429, Duration.ofSeconds(30));
        })).isInstanceOf(RetryableModelException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void concurrentCallsShouldNotShareRetryCounters() throws Exception {
        RetryTemplate template = template();
        ConcurrentHashMap<Integer, AtomicInteger> attempts = new ConcurrentHashMap<>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int task = i;
                futures.add(executor.submit(() -> template.execute(context -> {
                    int current = attempts.computeIfAbsent(task, ignored -> new AtomicInteger())
                            .incrementAndGet();
                    if (current == 1) {
                        throw new RetryableModelException(
                                ModelFailureCategory.UPSTREAM_UNAVAILABLE, 503, null);
                    }
                    return current;
                })));
            }
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(2);
            }
        }
        assertThat(attempts.values()).allMatch(count -> count.get() == 2);
    }

    private RetryTemplate template() {
        ModelCallProperties properties = new ModelCallProperties();
        properties.setInitialBackoff(Duration.ofMillis(10));
        properties.setMaxBackoff(Duration.ofMillis(50));
        properties.setJitterFactor(0);
        ModelFailureClassifier classifier = new ModelFailureClassifier(properties);
        return new ModelCallConfiguration().retryTemplate(properties, classifier);
    }
}

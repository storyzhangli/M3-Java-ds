package com.zl.d29.model;

import com.zl.d29.common.ResultCode;
import com.zl.d29.config.ModelCallProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelFailureClassifierTest {

    private final ModelCallProperties properties = new ModelCallProperties();
    private final ModelFailureClassifier classifier = new ModelFailureClassifier(properties);

    @Test
    void rateLimitShouldRetryOnlyWithinRetryAfterBudget() {
        ModelFailure shortDelay = classifier.httpFailure(429, "1", "");
        assertThat(shortDelay.category()).isEqualTo(ModelFailureCategory.RATE_LIMITED);
        assertThat(shortDelay.retryable()).isTrue();
        assertThat(shortDelay.retryAfter()).isEqualTo(Duration.ofSeconds(1));

        ModelFailure longDelay = classifier.httpFailure(429, "30", "");
        assertThat(classifier.isRetryable(
                new RetryableModelException(longDelay.category(), 429, longDelay.retryAfter())))
                .isFalse();
    }

    @Test
    void permanentQuotaAndClientErrorsShouldNotRetry() {
        ModelFailure quota = classifier.httpFailure(
                429, "1", "{\"error\":{\"code\":\"insufficient_quota\"}}");
        assertThat(quota.category()).isEqualTo(ModelFailureCategory.QUOTA_EXHAUSTED);
        assertThat(quota.retryable()).isFalse();
        assertThat(classifier.resultCode(
                new NonRetryableModelException(quota.category(), 429)))
                .isEqualTo(ResultCode.AI_CONFIG_ERROR);

        ModelFailure unauthorized = classifier.httpFailure(401, null, "");
        assertThat(unauthorized.category()).isEqualTo(ModelFailureCategory.AUTHENTICATION_FAILED);
        assertThat(unauthorized.retryable()).isFalse();
    }

    @Test
    void connectFailureIsRetryableButReadTimeoutIsNot() {
        ResourceAccessException connect =
                new ResourceAccessException("connect", new ConnectException("refused"));
        assertThat(classifier.classify(connect).category())
                .isEqualTo(ModelFailureCategory.CONNECT_FAILURE);
        assertThat(classifier.isRetryable(connect)).isTrue();

        ResourceAccessException read =
                new ResourceAccessException("read", new SocketTimeoutException("timed out"));
        assertThat(classifier.classify(read).category())
                .isEqualTo(ModelFailureCategory.READ_TIMEOUT);
        assertThat(classifier.isRetryable(read)).isFalse();
    }

    @Test
    void responseHandlerShouldUseSameClassificationAndNeverExposeBody() {
        ClassifiedResponseErrorHandler handler = new ClassifiedResponseErrorHandler(classifier);
        MockClientHttpResponse response =
                new MockClientHttpResponse("private upstream body".getBytes(), HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set("Retry-After", "1");

        assertThatThrownBy(() -> handler.handleError(response))
                .isInstanceOf(RetryableModelException.class)
                .hasMessageNotContaining("private upstream body");

        MockClientHttpResponse unauthorized =
                new MockClientHttpResponse("token detail".getBytes(), HttpStatus.UNAUTHORIZED);
        assertThatThrownBy(() -> handler.handleError(unauthorized))
                .isInstanceOf(NonRetryableModelException.class)
                .hasMessageNotContaining("token detail");
    }
}

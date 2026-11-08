package com.zl.d26.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zl.d26.common.AiConfigurationException;
import com.zl.d26.common.ResultCode;
import com.zl.d26.config.ModelCallProperties;
import io.netty.channel.ConnectTimeoutException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 同步、流式和 HTTP 异常的统一内部分类器，也是唯一的重试可适性判定入口。
 */
@Component
public class ModelFailureClassifier {

    private final ModelCallProperties properties;

    public ModelFailureClassifier(ModelCallProperties properties) {
        this.properties = properties;
    }

    public ModelFailure classify(Throwable throwable) {
        if (throwable instanceof RetryableModelException retryable) {
            return new ModelFailure(retryable.getCategory(), retryable.getHttpStatus(),
                    true, retryable.getRetryAfter());
        }
        if (throwable instanceof NonRetryableModelException nonRetryable) {
            return new ModelFailure(nonRetryable.getCategory(), nonRetryable.getHttpStatus(),
                    false, null);
        }
        if (throwable instanceof AiConfigurationException) {
            return failure(ModelFailureCategory.AUTHENTICATION_FAILED);
        }
        if (throwable instanceof WebClientResponseException response) {
            return fromHttpStatus(response.getStatusCode().value(), null, null);
        }
        if (throwable instanceof HttpStatusCodeException response) {
            return fromHttpStatus(response.getStatusCode().value(), null, null);
        }
        if (throwable instanceof WebClientRequestException request) {
            return fromNetworkCause(request);
        }
        if (throwable instanceof ResourceAccessException access) {
            return fromNetworkCause(access);
        }
        if (throwable instanceof CancellationException) {
            return failure(ModelFailureCategory.CANCELLED);
        }
        if (throwable instanceof TimeoutException) {
            return failure(ModelFailureCategory.DEADLINE_EXCEEDED);
        }
        if (throwable instanceof JsonProcessingException) {
            return failure(ModelFailureCategory.INVALID_RESPONSE);
        }
        if (throwable instanceof NonTransientAiException) {
            return failure(ModelFailureCategory.PARAMETER_ERROR);
        }
        if (throwable instanceof TransientAiException) {
            return failure(ModelFailureCategory.UPSTREAM_UNAVAILABLE);
        }
        if (throwable instanceof IOException) {
            return failure(ModelFailureCategory.NETWORK_IO);
        }
        return failure(ModelFailureCategory.UNKNOWN);
    }

    /**
     * 仅明确批准的失败可重试。Retry-After 超过单次退避上限时不再发起下一次请求。
     */
    public boolean isRetryable(Throwable throwable) {
        ModelFailure failure = classify(throwable);
        if (!failure.retryable()) {
            return false;
        }
        return failure.retryAfter() == null
                || failure.retryAfter().compareTo(properties.getMaxBackoff()) <= 0;
    }

    public Duration retryAfter(Throwable throwable) {
        ModelFailure failure = classify(throwable);
        return failure.retryable() ? failure.retryAfter() : null;
    }

    public ModelFailure httpFailure(int status, String retryAfterHeader, String responseBody) {
        return fromHttpStatus(status, retryAfterHeader, responseBody);
    }

    public ResultCode resultCode(Throwable throwable) {
        ModelFailureCategory category = classify(throwable).category();
        return switch (category) {
            case AUTHENTICATION_FAILED, AUTHORIZATION_FAILED, PARAMETER_ERROR,
                    MODEL_OR_RESOURCE_NOT_FOUND, BUSINESS_REJECTED, QUOTA_EXHAUSTED ->
                    ResultCode.AI_CONFIG_ERROR;
            case RATE_LIMITED, UPSTREAM_UNAVAILABLE, CONNECT_FAILURE, READ_TIMEOUT,
                    NETWORK_IO -> ResultCode.AI_UNAVAILABLE;
            default -> ResultCode.SYSTEM_ERROR;
        };
    }

    private ModelFailure fromHttpStatus(int status, String retryAfterHeader, String responseBody) {
        Duration retryAfter = parseRetryAfter(retryAfterHeader);
        if (status == 429) {
            if (isPermanentQuotaBody(responseBody)) {
                return new ModelFailure(ModelFailureCategory.QUOTA_EXHAUSTED, status, false, null);
            }
            return new ModelFailure(ModelFailureCategory.RATE_LIMITED, status, true, retryAfter);
        }
        if (status == 502 || status == 503 || status == 504) {
            return new ModelFailure(ModelFailureCategory.UPSTREAM_UNAVAILABLE, status, true, null);
        }
        if (status == 400 || status == 422) {
            return new ModelFailure(ModelFailureCategory.PARAMETER_ERROR, status, false, null);
        }
        if (status == 401) {
            return new ModelFailure(ModelFailureCategory.AUTHENTICATION_FAILED, status, false, null);
        }
        if (status == 403) {
            return new ModelFailure(ModelFailureCategory.AUTHORIZATION_FAILED, status, false, null);
        }
        if (status == 402) {
            return new ModelFailure(ModelFailureCategory.QUOTA_EXHAUSTED, status, false, null);
        }
        if (status == 404) {
            return new ModelFailure(ModelFailureCategory.MODEL_OR_RESOURCE_NOT_FOUND, status, false, null);
        }
        if (status == 408) {
            return new ModelFailure(ModelFailureCategory.READ_TIMEOUT, status, false, null);
        }
        if (status == 409) {
            return new ModelFailure(ModelFailureCategory.BUSINESS_REJECTED, status, false, null);
        }
        if (status >= 400 && status < 500) {
            return new ModelFailure(ModelFailureCategory.PARAMETER_ERROR, status, false, null);
        }
        if (status >= 500) {
            return new ModelFailure(ModelFailureCategory.UPSTREAM_UNAVAILABLE, status, false, null);
        }
        return new ModelFailure(ModelFailureCategory.UNKNOWN, status, false, null);
    }

    private ModelFailure fromNetworkCause(Throwable throwable) {
        if (hasCause(throwable, UnknownHostException.class)) {
            return failure(ModelFailureCategory.AUTHENTICATION_FAILED);
        }
        if (hasCause(throwable, SSLHandshakeException.class)) {
            return failure(ModelFailureCategory.AUTHENTICATION_FAILED);
        }
        if (hasCause(throwable, HttpConnectTimeoutException.class)) {
            return new ModelFailure(ModelFailureCategory.CONNECT_FAILURE, null, true, null);
        }
        if (hasCause(throwable, SocketTimeoutException.class)
                || hasCause(throwable, HttpTimeoutException.class)
                || hasCause(throwable, io.netty.handler.timeout.ReadTimeoutException.class)) {
            return failure(ModelFailureCategory.READ_TIMEOUT);
        }
        if (hasCause(throwable, ConnectException.class)
                || hasCause(throwable, ConnectTimeoutException.class)
                || hasCause(throwable, NoRouteToHostException.class)) {
            return new ModelFailure(ModelFailureCategory.CONNECT_FAILURE, null, true, null);
        }
        if (hasCause(throwable, CancellationException.class)) {
            return failure(ModelFailureCategory.CANCELLED);
        }
        return failure(ModelFailureCategory.NETWORK_IO);
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isPermanentQuotaBody(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("insufficient_quota")
                || lower.contains("quota_exceeded")
                || lower.contains("billing_hard_limit")
                || lower.contains("insufficient balance")
                || lower.contains("余额不足")
                || lower.contains("额度不足");
    }

    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                var at = java.time.ZonedDateTime.parse(
                        value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration delay = Duration.between(java.time.ZonedDateTime.now(at.getZone()), at);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException ignoredDate) {
                return null;
            }
        }
    }

    private ModelFailure failure(ModelFailureCategory category) {
        return new ModelFailure(category, null, false, null);
    }
}

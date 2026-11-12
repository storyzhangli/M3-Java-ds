package com.zl.d29.config;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 模型 HTTP 超时和同步调用唯一重试层的配置。
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.model-call")
public class ModelCallProperties {

    /** TCP 建连超时。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 单次 HTTP 响应读取超时。 */
    private Duration responseTimeout = Duration.ofSeconds(30);

    /** 同步调用全部尝试和退避共享的总 Deadline。 */
    private Duration totalDeadline = Duration.ofSeconds(90);

    /** 流式响应总时长上限；空闲间隔仍由 app.ai.stream-timeout-ms 控制。 */
    private Duration streamTotalTimeout = Duration.ofSeconds(180);

    /** 同步调用总尝试次数（首次调用也计数）。 */
    @Min(value = 1, message = "maxAttempts 必须 >= 1")
    @Max(value = 3, message = "maxAttempts 必须 <= 3")
    private int maxAttempts = 2;

    /** 首次退避。 */
    private Duration initialBackoff = Duration.ofMillis(500);

    /** 单次退避上限；Retry-After 超过该值时不再重试。 */
    private Duration maxBackoff = Duration.ofSeconds(10);

    /** 指数退避随机抖动比例。 */
    @DecimalMin(value = "0.0", message = "jitterFactor 必须 >= 0")
    @DecimalMax(value = "0.8", message = "jitterFactor 必须 <= 0.8")
    private double jitterFactor = 0.2;

    @AssertTrue(message = "模型调用超时必须均为正值")
    public boolean isTimeoutPositive() {
        return positive(connectTimeout) && positive(responseTimeout) && positive(totalDeadline)
                && positive(streamTotalTimeout);
    }

    @AssertTrue(message = "initialBackoff 必须大于 0 且不超过 maxBackoff")
    public boolean isBackoffRangeValid() {
        return positive(initialBackoff) && positive(maxBackoff)
                && initialBackoff.compareTo(maxBackoff) <= 0;
    }

    @AssertTrue(message = "totalDeadline 必须覆盖最坏尝试次数、建连/响应超时和退避")
    public boolean isDeadlineBudgetValid() {
        if (maxAttempts < 1) {
            return true;
        }
        Duration perAttempt = connectTimeout.plus(responseTimeout);
        Duration worstAttempts = perAttempt.multipliedBy(maxAttempts);
        Duration allBackoffs = maxBackoff.multipliedBy(Math.max(0, maxAttempts - 1));
        return totalDeadline.compareTo(worstAttempts.plus(allBackoffs)) >= 0;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}

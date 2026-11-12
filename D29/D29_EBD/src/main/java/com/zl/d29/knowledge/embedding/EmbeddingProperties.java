package com.zl.d29.knowledge.embedding;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Externalized Embedding model and index configuration.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    @NotNull
    private EmbeddingProvider provider = EmbeddingProvider.LOCAL;

    @NotBlank
    private String modelName = "local-hash-ngram-v1";

    @NotBlank
    private String modelVersion = "2026.11";

    @Min(1)
    @Max(8192)
    private int dimensions = 384;

    @Min(1)
    private int maxInputLength = 12_000;

    @NotNull
    private EmbeddingLengthUnit lengthUnit = EmbeddingLengthUnit.UNICODE_CODE_POINTS;

    @NotNull
    private DistanceMetric distanceMetric = DistanceMetric.COSINE;

    @NotBlank
    private String indexVersion = "d29-local-384-v1";

    @NotBlank
    private String apiUrl = "local://hash-ngram";

    /**
     * Only supplied through an environment variable. Never include this field in
     * logs, responses, evidence, or Lombok toString output.
     */
    @ToString.Exclude
    private String apiKey = "";

    private boolean keyRequired;

    @NotNull
    @DecimalMin("0.0")
    private BigDecimal costPerMillionInputTokensUsd = BigDecimal.ZERO;

    private boolean enabled = true;

    @AssertTrue(message = "Embedding 配置必须启用")
    public boolean isEmbeddingEnabled() {
        return enabled;
    }

    public void requireValid() {
        if (!enabled) {
            throw new IllegalArgumentException("Embedding 配置未启用");
        }
        if (provider == null || modelName == null || modelName.isBlank()
                || modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("Embedding provider/model/version 不能为空");
        }
        if (dimensions < 1 || dimensions > 8192) {
            throw new IllegalArgumentException("Embedding dimensions 必须在 1-8192");
        }
        if (maxInputLength < 1) {
            throw new IllegalArgumentException("Embedding max-input-length 必须大于 0");
        }
        if (lengthUnit == null || distanceMetric == null
                || indexVersion == null || indexVersion.isBlank()
                || apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalArgumentException("Embedding 索引与长度配置不能为空");
        }
        if (costPerMillionInputTokensUsd == null
                || costPerMillionInputTokensUsd.signum() < 0) {
            throw new IllegalArgumentException("Embedding 价格不能为负数");
        }
    }
}

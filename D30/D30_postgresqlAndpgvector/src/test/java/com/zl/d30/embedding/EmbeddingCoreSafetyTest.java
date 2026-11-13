package com.zl.d30.embedding;

import com.zl.d30.common.ResultCode;
import com.zl.d30.knowledge.embedding.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingCoreSafetyTest {

    private final EmbeddingVectorValidator vectorValidator = new EmbeddingVectorValidator();

    private final IndexCompatibilityValidator indexValidator = new IndexCompatibilityValidator();

    @Test
    void vectorValidatorShouldRejectInvalidShapesAndValues() {
        assertInvalid(null, 3, "空向量");
        assertInvalid(new float[0], 3, "空向量");
        assertInvalid(new float[]{1, 2}, 3, "维度不一致");
        assertInvalid(new float[]{1, Float.NaN, 0}, 3, "NaN");
        assertInvalid(new float[]{1, Float.POSITIVE_INFINITY, 0}, 3, "Infinity");
        assertInvalid(new float[]{0, 0, 0}, 3, "零向量");
        vectorValidator.validate(new float[]{0.1f, 0.2f, 0.3f}, 3);
    }

    @Test
    void sameModelAndIndexShouldBeCompatible() {
        EmbeddingModelDescriptor local = localDescriptor();

        IndexCompatibility result = indexValidator.check(
                local.indexMetadata(), local);

        assertThat(result.compatible()).isTrue();
    }

    @Test
    void differentDimensionsMustBeRejected() {
        EmbeddingModelDescriptor local = localDescriptor();

        IndexCompatibility result = indexValidator.check(
                local.indexMetadata(),
                EmbeddingModelCatalog.OPENAI_TEXT_EMBEDDING_3_SMALL);

        assertThat(result.compatible()).isFalse();
        assertThat(result.reason()).contains("provider");

        EmbeddingModelDescriptor sameProviderDifferentDimensions =
                new EmbeddingModelDescriptor(
                        local.provider(),
                        local.modelName(),
                        local.modelVersion(),
                        512,
                        12_000,
                        local.lengthUnit(),
                        VectorSpaceIdentity.create(
                                local.provider(), local.modelName(), local.modelVersion(), 512),
                        local.indexVersion(),
                        local.distanceMetric(),
                        BigDecimal.ZERO,
                        false,
                        local.apiUrl());
        IndexCompatibility dimensionsResult = indexValidator.check(
                local.indexMetadata(), sameProviderDifferentDimensions);
        assertThat(dimensionsResult.compatible()).isFalse();
        assertThat(dimensionsResult.reason()).contains("维度");
    }

    @Test
    void sameDimensionsButDifferentModelMustBeIsolated() {
        EmbeddingModelDescriptor local = localDescriptor();
        EmbeddingModelDescriptor differentModel = new EmbeddingModelDescriptor(
                EmbeddingProvider.LOCAL,
                "local-hash-ngram-v2",
                "2026.11",
                384,
                12_000,
                local.lengthUnit(),
                VectorSpaceIdentity.create(
                        EmbeddingProvider.LOCAL, "local-hash-ngram-v2", "2026.11", 384),
                "d30-local-384-v2",
                DistanceMetric.COSINE,
                BigDecimal.ZERO,
                false,
                "local://hash-ngram-v2");

        IndexCompatibility result = indexValidator.check(
                local.indexMetadata(), differentModel);

        assertThat(result.compatible()).isFalse();
        assertThat(result.reason()).contains("模型名称");
        assertThatThrownBy(() -> indexValidator.requireCompatible(
                local.indexMetadata(), differentModel))
                .isInstanceOfSatisfying(EmbeddingException.class, ex ->
                        assertThat(ex.getResultCode())
                                .isEqualTo(ResultCode.VECTOR_INDEX_INCOMPATIBLE));
    }

    @Test
    void indexVersionAndDistanceMetricMustMatch() {
        EmbeddingModelDescriptor local = localDescriptor();
        var wrongIndexVersion = new com.zl.d30.knowledge.embedding.VectorIndexMetadata(
                local.provider(),
                local.modelName(),
                local.modelVersion(),
                local.dimensions(),
                local.vectorSpaceId(),
                "other-index-version",
                local.distanceMetric());
        assertThat(indexValidator.check(wrongIndexVersion, local).compatible()).isFalse();

        var wrongMetric = new com.zl.d30.knowledge.embedding.VectorIndexMetadata(
                local.provider(),
                local.modelName(),
                local.modelVersion(),
                local.dimensions(),
                local.vectorSpaceId(),
                local.indexVersion(),
                DistanceMetric.EUCLIDEAN);
        assertThat(indexValidator.check(wrongMetric, local).compatible()).isFalse();
    }

    @Test
    void propertiesShouldValidateAndNeverExposeApiKeyInToString() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setApiKey("secret-value-must-not-print");

        assertThat(properties.toString()).doesNotContain("secret-value-must-not-print");
        assertThat(Validation.buildDefaultValidatorFactory().getValidator()
                .validate(properties)).isEmpty();

        properties.setDimensions(9000);
        assertThatThrownBy(properties::requireValid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void officialCandidatesShouldUseVerifiedDimensionsAndPrices() {
        var small = EmbeddingModelCatalog.OPENAI_TEXT_EMBEDDING_3_SMALL;
        var large = EmbeddingModelCatalog.OPENAI_TEXT_EMBEDDING_3_LARGE;

        assertThat(small.dimensions()).isEqualTo(1536);
        assertThat(small.maxInputLength()).isEqualTo(8192);
        assertThat(small.costPerMillionInputTokensUsd())
                .isEqualByComparingTo("0.02");
        assertThat(large.dimensions()).isEqualTo(3072);
        assertThat(large.maxInputLength()).isEqualTo(8192);
        assertThat(large.costPerMillionInputTokensUsd())
                .isEqualByComparingTo("0.13");
        assertThat(small.vectorSpaceId()).isNotEqualTo(large.vectorSpaceId());
    }

    private EmbeddingModelDescriptor localDescriptor() {
        return EmbeddingModelDescriptor.from(new EmbeddingProperties());
    }

    private void assertInvalid(float[] vector, int dimensions, String message) {
        assertThatThrownBy(() -> vectorValidator.validate(vector, dimensions))
                .isInstanceOfSatisfying(EmbeddingException.class, ex -> {
                    assertThat(ex.getResultCode())
                            .isEqualTo(ResultCode.EMBEDDING_RESPONSE_INVALID);
                    assertThat(ex.getMessage()).contains(message);
                });
    }
}

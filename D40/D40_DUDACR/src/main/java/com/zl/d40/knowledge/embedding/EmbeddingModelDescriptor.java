package com.zl.d40.knowledge.embedding;

import com.zl.d40.knowledge.embedding.DistanceMetric;
import com.zl.d40.knowledge.embedding.EmbeddingLengthUnit;
import com.zl.d40.knowledge.embedding.EmbeddingProperties;
import com.zl.d40.knowledge.embedding.EmbeddingProvider;
import com.zl.d40.knowledge.embedding.VectorIndexMetadata;
import com.zl.d40.knowledge.embedding.VectorSpaceIdentity;

import java.math.BigDecimal;

/**
 * Immutable model and vector-space metadata. It deliberately contains no API key.
 */
public record EmbeddingModelDescriptor(
        EmbeddingProvider provider,
        String modelName,
        String modelVersion,
        int dimensions,
        int maxInputLength,
        EmbeddingLengthUnit lengthUnit,
        String vectorSpaceId,
        String indexVersion,
        DistanceMetric distanceMetric,
        BigDecimal costPerMillionInputTokensUsd,
        boolean keyRequired,
        String apiUrl) {

    public static EmbeddingModelDescriptor from(EmbeddingProperties properties) {
        properties.requireValid();
        return new EmbeddingModelDescriptor(
                properties.getProvider(),
                properties.getModelName().strip(),
                properties.getModelVersion().strip(),
                properties.getDimensions(),
                properties.getMaxInputLength(),
                properties.getLengthUnit(),
                VectorSpaceIdentity.create(
                        properties.getProvider(),
                        properties.getModelName(),
                        properties.getModelVersion(),
                        properties.getDimensions()),
                properties.getIndexVersion().strip(),
                properties.getDistanceMetric(),
                properties.getCostPerMillionInputTokensUsd(),
                properties.isKeyRequired(),
                properties.getApiUrl().strip());
    }

    public VectorIndexMetadata indexMetadata() {
        return new VectorIndexMetadata(
                provider,
                modelName,
                modelVersion,
                dimensions,
                vectorSpaceId,
                indexVersion,
                distanceMetric);
    }
}

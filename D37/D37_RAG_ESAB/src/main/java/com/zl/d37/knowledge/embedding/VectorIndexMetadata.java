package com.zl.d37.knowledge.embedding;

import com.zl.d37.knowledge.embedding.DistanceMetric;
import com.zl.d37.knowledge.embedding.EmbeddingProvider;

/**
 * Metadata that must be persisted alongside every vector index.
 */
public record VectorIndexMetadata(
        EmbeddingProvider provider,
        String modelName,
        String modelVersion,
        int dimensions,
        String vectorSpaceId,
        String indexVersion,
        DistanceMetric distanceMetric) {
}

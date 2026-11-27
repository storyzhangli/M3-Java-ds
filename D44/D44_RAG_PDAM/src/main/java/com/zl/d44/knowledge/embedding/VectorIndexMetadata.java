package com.zl.d44.knowledge.embedding;

import com.zl.d44.knowledge.embedding.DistanceMetric;
import com.zl.d44.knowledge.embedding.EmbeddingProvider;

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

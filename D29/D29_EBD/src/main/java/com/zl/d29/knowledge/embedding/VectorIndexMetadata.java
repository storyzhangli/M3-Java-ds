package com.zl.d29.knowledge.embedding;

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

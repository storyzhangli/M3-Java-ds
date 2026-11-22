package com.zl.d39.knowledge.embedding;

import com.zl.d39.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d39.knowledge.embedding.VectorIndexMetadata;

/**
 * Compatibility decision for writing a candidate model's vectors into an index.
 */
public record IndexCompatibility(
        boolean compatible,
        String reason,
        VectorIndexMetadata index,
        EmbeddingModelDescriptor candidate) {
}

package com.zl.d38.knowledge.embedding;

import com.zl.d38.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d38.knowledge.embedding.VectorIndexMetadata;

/**
 * Compatibility decision for writing a candidate model's vectors into an index.
 */
public record IndexCompatibility(
        boolean compatible,
        String reason,
        VectorIndexMetadata index,
        EmbeddingModelDescriptor candidate) {
}

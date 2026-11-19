package com.zl.d36.knowledge.embedding;

import com.zl.d36.knowledge.embedding.ChunkEmbedding;
import com.zl.d36.knowledge.embedding.EmbeddingCostEstimate;
import com.zl.d36.knowledge.embedding.EmbeddingModelDescriptor;

import java.util.List;

/**
 * Read-only embedding preview for one chunking generation.
 */
public record EmbeddingBatchResult(
        String documentId,
        String title,
        String version,
        EmbeddingModelDescriptor model,
        VectorIndexMetadata index,
        int chunkCount,
        int totalInputCharacters,
        long elapsedMs,
        EmbeddingUsage usage,
        EmbeddingCostEstimate cost,
        List<ChunkEmbedding> embeddings) {

    public EmbeddingBatchResult {
        embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
    }
}

package com.zl.d29.knowledge.embedding;

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

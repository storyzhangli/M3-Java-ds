package com.zl.d30.knowledge.embedding;

import com.zl.d30.knowledge.embedding.ChunkEmbedding;
import com.zl.d30.knowledge.embedding.EmbeddingCostEstimate;
import com.zl.d30.knowledge.embedding.EmbeddingUsage;
import com.zl.d30.knowledge.embedding.VectorIndexMetadata;

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

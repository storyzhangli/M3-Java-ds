package com.zl.d31.knowledge.embedding;

import com.zl.d31.knowledge.chunking.ChunkSource;

import java.util.List;

/**
 * One vector plus all source metadata needed for index persistence and citation.
 */
public record ChunkEmbedding(
        String documentId,
        String title,
        String version,
        int chunkIndex,
        List<Integer> pageNumbers,
        List<ChunkSource> sources,
        String inputHash,
        int inputCharacters,
        int dimensions,
        float[] vector,
        int attempts) {

    public ChunkEmbedding {
        pageNumbers = pageNumbers == null ? List.of() : List.copyOf(pageNumbers);
        sources = sources == null ? List.of() : List.copyOf(sources);
        vector = vector == null ? null : vector.clone();
    }

    @Override
    public float[] vector() {
        return vector == null ? null : vector.clone();
    }
}

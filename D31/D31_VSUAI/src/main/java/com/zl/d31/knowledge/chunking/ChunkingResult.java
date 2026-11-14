package com.zl.d31.knowledge.chunking;

import java.util.List;

/**
 * Chunk output and reproducible configuration snapshot.
 */
public record ChunkingResult(
        String documentId,
        String title,
        String version,
        ChunkStrategy strategy,
        String lengthUnit,
        int chunkSize,
        int overlap,
        int minChunkSize,
        int maxChunkSize,
        int sourceCharacterCount,
        int cleanedCharacterCount,
        ChunkingMetrics metrics,
        List<DocumentChunk> chunks) {

    public ChunkingResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}

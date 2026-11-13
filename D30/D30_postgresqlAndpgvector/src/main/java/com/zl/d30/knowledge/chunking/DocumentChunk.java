package com.zl.d30.knowledge.chunking;

import java.util.List;

/**
 * A traceable chunk produced from one document version.
 */
public record DocumentChunk(
        String documentId,
        String title,
        String version,
        int chunkIndex,
        ChunkStrategy strategy,
        String content,
        int characterCount,
        List<Integer> pageNumbers,
        List<ChunkSource> sources,
        ChunkBoundaryType endBoundary) {

    public DocumentChunk {
        pageNumbers = pageNumbers == null ? List.of() : List.copyOf(pageNumbers);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}

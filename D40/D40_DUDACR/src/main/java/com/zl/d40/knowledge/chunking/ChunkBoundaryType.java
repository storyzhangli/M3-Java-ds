package com.zl.d40.knowledge.chunking;

/**
 * End boundary selected for a chunk.
 */
public enum ChunkBoundaryType {
    PARAGRAPH,
    SENTENCE,
    WHITESPACE,
    HARD_LIMIT,
    DOCUMENT_END
}

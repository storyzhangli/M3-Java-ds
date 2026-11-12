package com.zl.d29.knowledge.chunking;

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

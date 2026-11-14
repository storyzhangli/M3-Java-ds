package com.zl.d31.knowledge.chunking;

/**
 * Fixed-length and structure-aware results for the same cleaned document.
 */
public record ChunkingComparison(
        ChunkingResult fixedLength,
        ChunkingResult structureAware) {
}

package com.zl.d29.knowledge.chunking;

import com.zl.d29.knowledge.chunking.ChunkingResult;

/**
 * Fixed-length and structure-aware results for the same cleaned document.
 */
public record ChunkingComparison(
        ChunkingResult fixedLength,
        ChunkingResult structureAware) {
}

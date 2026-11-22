package com.zl.d39.knowledge.chunking;

import com.zl.d39.knowledge.chunking.ChunkingResult;

/**
 * Fixed-length and structure-aware results for the same cleaned document.
 */
public record ChunkingComparison(
        ChunkingResult fixedLength,
        ChunkingResult structureAware) {
}

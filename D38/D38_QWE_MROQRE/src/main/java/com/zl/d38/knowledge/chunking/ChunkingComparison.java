package com.zl.d38.knowledge.chunking;

import com.zl.d38.knowledge.chunking.ChunkingResult;

/**
 * Fixed-length and structure-aware results for the same cleaned document.
 */
public record ChunkingComparison(
        ChunkingResult fixedLength,
        ChunkingResult structureAware) {
}

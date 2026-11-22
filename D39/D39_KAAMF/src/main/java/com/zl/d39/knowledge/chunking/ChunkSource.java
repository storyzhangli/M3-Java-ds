package com.zl.d39.knowledge.chunking;

/**
 * One page/paragraph source covered by a chunk.
 */
public record ChunkSource(Integer pageNumber, Integer paragraphIndex) {
}

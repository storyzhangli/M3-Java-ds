package com.zl.d29.knowledge.chunking;

import com.zl.d29.knowledge.chunking.ChunkBoundaryType;

import java.util.Map;

/**
 * Metrics computed from actual chunk output.
 */
public record ChunkingMetrics(
        int chunkCount,
        double averageCharacterCount,
        int minimumCharacterCount,
        int maximumCharacterCount,
        Map<ChunkBoundaryType, Long> boundaryCounts,
        Map<String, Long> lengthDistribution) {
}

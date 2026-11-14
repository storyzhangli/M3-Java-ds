package com.zl.d31.knowledge.chunking;

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

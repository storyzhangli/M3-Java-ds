package com.zl.d31.knowledge.embedding;

/**
 * Usage returned by the provider. Local models do not invent token counts.
 */
public record EmbeddingUsage(
        String status,
        Integer inputTokens,
        String tokenSource,
        int attempts) {
}

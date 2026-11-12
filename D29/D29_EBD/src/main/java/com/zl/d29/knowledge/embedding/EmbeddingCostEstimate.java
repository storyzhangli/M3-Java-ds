package com.zl.d29.knowledge.embedding;

import java.math.BigDecimal;

/**
 * API-fee estimate. Local compute cost and assistant token cost are separate.
 */
public record EmbeddingCostEstimate(
        BigDecimal pricePerMillionInputTokensUsd,
        Integer inputTokens,
        BigDecimal estimatedApiCostUsd,
        String basis) {
}

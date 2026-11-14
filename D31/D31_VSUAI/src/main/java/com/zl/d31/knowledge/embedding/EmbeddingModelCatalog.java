package com.zl.d31.knowledge.embedding;

import java.math.BigDecimal;

/**
 * Official cloud candidates observed on 2026-11-12. They are metadata only in
 * this iteration and are not the active billable model.
 */
public final class EmbeddingModelCatalog {

    public static final EmbeddingModelDescriptor OPENAI_TEXT_EMBEDDING_3_SMALL =
            openAi("text-embedding-3-small", 1536, "0.02", "openai-3-small-1536-v1");

    public static final EmbeddingModelDescriptor OPENAI_TEXT_EMBEDDING_3_LARGE =
            openAi("text-embedding-3-large", 3072, "0.13", "openai-3-large-3072-v1");

    private EmbeddingModelCatalog() {
    }

    private static EmbeddingModelDescriptor openAi(String name,
                                                   int dimensions,
                                                   String price,
                                                   String indexVersion) {
        return new EmbeddingModelDescriptor(
                EmbeddingProvider.OPENAI,
                name,
                "current",
                dimensions,
                8192,
                EmbeddingLengthUnit.TOKENS,
                VectorSpaceIdentity.create(
                        EmbeddingProvider.OPENAI, name, "current", dimensions),
                indexVersion,
                DistanceMetric.COSINE,
                new BigDecimal(price),
                true,
                "https://api.openai.com/v1/embeddings");
    }
}

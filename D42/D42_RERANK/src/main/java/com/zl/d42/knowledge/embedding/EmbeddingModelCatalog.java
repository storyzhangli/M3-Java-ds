package com.zl.d42.knowledge.embedding;

import com.zl.d42.knowledge.embedding.DistanceMetric;
import com.zl.d42.knowledge.embedding.EmbeddingLengthUnit;
import com.zl.d42.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d42.knowledge.embedding.EmbeddingProvider;
import com.zl.d42.knowledge.embedding.VectorSpaceIdentity;

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

    /**
     * 探针实测冻结（2026-11-21）：qwen3.7-text-embedding，1024 维，服务端 L2 归一化，
     * encoding_format=float 可用，批量 input 数组可用且 data[].index 对齐。
     * 服务端无版本回显，modelVersion 记为 "current"（已登记假设）。
     * 价格未公开，cost 置 null（不参与任何计费结论）；apiUrl 为公共 compatible-mode
     * 候选元数据，真实 baseUrl 由环境变量 BAILIAN_EMBEDDING_BASE_URL 注入。
     */
    public static final EmbeddingModelDescriptor BAILIAN_QWEN37_TEXT_EMBEDDING =
            new EmbeddingModelDescriptor(
                    EmbeddingProvider.BAILIAN,
                    "qwen3.7-text-embedding",
                    "current",
                    1024,
                    8192,
                    EmbeddingLengthUnit.TOKENS,
                    VectorSpaceIdentity.create(
                            EmbeddingProvider.BAILIAN, "qwen3.7-text-embedding", "current", 1024),
                    "two-bailian-1024-v1",
                    DistanceMetric.COSINE,
                    null,
                    true,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings");

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

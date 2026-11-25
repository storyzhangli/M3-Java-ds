package com.zl.d42.knowledge.retrieval.service;

import com.zl.d42.common.ResultCode;
import com.zl.d42.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d42.knowledge.retrieval.RetrievalException;
import com.zl.d42.knowledge.retrieval.config.RetrievalProperties;
import com.zl.d42.knowledge.retrieval.model.QueryVector;
import com.zl.d42.knowledge.retrieval.support.QueryVectorValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 查询文本向量化服务。
 *
 * <p>复用 D31 的同一个 {@link EmbeddingModel}（本地确定性 384 维）与同一个
 * {@link EmbeddingModelDescriptor}（向量空间标识），保证查询向量与入库向量属于同一
 * 模型版本与维度；不新建第二个模型，也不做任何维度转换。</p>
 *
 * <p>校验顺序：文本非空 → 长度上限（配置与模型输入上限取更严者）→ 模型生成 →
 * 维度/有限值/非全零校验。任何一步失败都抛出带稳定 {@code errorCode} 的业务异常。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class QueryEmbeddingService {

    private final EmbeddingModel embeddingModel;

    private final EmbeddingModelDescriptor descriptor;

    private final RetrievalProperties properties;

    public QueryEmbeddingService(EmbeddingModel embeddingModel,
                                 EmbeddingModelDescriptor descriptor,
                                 RetrievalProperties properties) {
        this.embeddingModel = embeddingModel;
        this.descriptor = descriptor;
        this.properties = properties;
        this.properties.validate();
    }

    /**
     * 生成查询向量。
     *
     * @param query 用户问题原文
     * @return 含归一化文本、向量、向量空间与耗时的真实结果
     */
    public QueryVector embed(String query) {
        String normalized = requireQuery(query);
        long start = System.nanoTime();
        float[] values;
        try {
            values = embeddingModel.embed(normalized);
        } catch (RetrievalException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new RetrievalException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "查询向量化失败，请检查 Embedding 模型配置与可用性",
                    "QUERY_EMBEDDING_FAILED", ex);
        }
        QueryVectorValidator.validate(values, descriptor.dimensions());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        log.info("查询向量化完成: dimensions={}, vectorSpace={}, elapsedMs={}",
                values.length, descriptor.vectorSpaceId(), elapsedMillis);
        return new QueryVector(normalized, values, descriptor.dimensions(),
                descriptor.vectorSpaceId(), descriptor.modelName(), elapsedMillis);
    }

    /**
     * 归一化并校验查询文本：非空、不超过配置上限、不超过模型输入上限。
     */
    public String requireQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new RetrievalException(ResultCode.EMBEDDING_INPUT_INVALID,
                    "检索 Query 不能为空", "QUERY_BLANK");
        }
        String trimmed = query.strip();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints > properties.getMaxQueryLength()) {
            throw new RetrievalException(ResultCode.EMBEDDING_INPUT_INVALID,
                    "检索 Query 长度 " + codePoints + " 超过上限 "
                            + properties.getMaxQueryLength() + "（Unicode 码点）",
                    "QUERY_TOO_LONG");
        }
        if (codePoints > descriptor.maxInputLength()) {
            throw new RetrievalException(ResultCode.EMBEDDING_INPUT_INVALID,
                    "检索 Query 长度超过 Embedding 模型输入上限 "
                            + descriptor.maxInputLength(), "QUERY_EXCEEDS_MODEL_LIMIT");
        }
        return trimmed;
    }

    /**
     * 当前向量空间描述（供结果摘要使用）。
     */
    public EmbeddingModelDescriptor descriptor() {
        return descriptor;
    }
}

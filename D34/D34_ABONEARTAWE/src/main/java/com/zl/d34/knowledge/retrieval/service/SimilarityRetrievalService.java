package com.zl.d34.knowledge.retrieval.service;

import com.zl.d34.common.ResultCode;
import com.zl.d34.knowledge.retrieval.RetrievalException;
import com.zl.d34.knowledge.retrieval.config.RetrievalProperties;
import com.zl.d34.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d34.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d34.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d34.knowledge.retrieval.model.QueryVector;
import com.zl.d34.knowledge.retrieval.service.QueryEmbeddingService;
import com.zl.d34.knowledge.retrieval.support.RetrievalFilterBuilder;
import com.zl.d34.knowledge.retrieval.support.RetrievalHitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 相似度检索编排服务。
 *
 * <p>职责边界：查询校验与向量化委托 {@link QueryEmbeddingService}；过滤表达式委托
 * {@link RetrievalFilterBuilder}；结果映射委托 {@link RetrievalHitMapper}；本类只负责
 * 参数解析、组装 {@link SearchRequest}、调用 {@link VectorStore} 并聚合耗时。</p>
 *
 * <p>已知能力限制（已实证 Spring AI 1.1.8）：{@link SearchRequest} 只接受查询文本，
 * 不接受预计算向量；{@code PgVectorStore} 内部会再次调用 EmbeddingModel。
 * 因此本地确定性模型下会进行一次额外的向量化（无外部费用、结果确定），
 * 远程 Embedding 模型需注意调用成本。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class SimilarityRetrievalService {

    private final VectorStore vectorStore;

    private final QueryEmbeddingService queryEmbeddingService;

    private final RetrievalProperties properties;

    private final RetrievalFilterBuilder filterBuilder = new RetrievalFilterBuilder();

    private final RetrievalHitMapper hitMapper = new RetrievalHitMapper();

    public SimilarityRetrievalService(VectorStore vectorStore,
                                      QueryEmbeddingService queryEmbeddingService,
                                      RetrievalProperties properties) {
        this.vectorStore = vectorStore;
        this.queryEmbeddingService = queryEmbeddingService;
        this.properties = properties;
        this.properties.validate();
    }

    /**
     * 执行 Top-K 余弦相似度检索。
     */
    public RetrievalResponse search(RetrievalQueryRequest request) {
        if (request == null) {
            throw new RetrievalException(ResultCode.PARAM_INVALID, "检索请求不能为空", "REQUEST_NULL");
        }
        long start = System.nanoTime();
        int effectiveTopK = resolveTopK(request.topK());
        QueryVector queryVector = queryEmbeddingService.embed(request.query());
        Filter.Expression filter = filterBuilder.build(
                request.documentId(), request.version(), request.strategy());

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(queryVector.query())
                .topK(effectiveTopK)
                .similarityThreshold(properties.getSimilarityThreshold());
        if (filter != null) {
            builder.filterExpression(filter);
        }
        SearchRequest searchRequest = builder.build();

        List<Document> documents;
        try {
            documents = vectorStore.similaritySearch(searchRequest);
        } catch (RuntimeException ex) {
            throw new RetrievalException(ResultCode.VECTOR_INDEX_FAILED,
                    "向量检索执行失败，请检查 pgvector 连接与向量表状态",
                    "SIMILARITY_SEARCH_FAILED", ex);
        }
        List<RetrievalHitView> hits = new ArrayList<>(documents == null ? 0 : documents.size());
        if (documents != null) {
            int rank = 1;
            for (Document document : documents) {
                hits.add(hitMapper.toView(document, rank++));
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        log.info("相似度检索完成: query=[{}], topK={}, filter=[{}], hits={}, elapsedMs={}",
                truncate(queryVector.query(), 40), effectiveTopK,
                filterDescription(request), hits.size(), elapsedMillis);
        return new RetrievalResponse(
                queryVector.query(),
                request.topK(),
                effectiveTopK,
                filterDescription(request),
                List.copyOf(hits),
                hits.size(),
                elapsedMillis,
                RetrievalHitMapper.SCORE_SEMANTIC,
                RetrievalHitMapper.DISTANCE_SEMANTIC,
                new RetrievalResponse.QueryEmbeddingSummary(
                        queryVector.dimensions(), queryVector.vectorSpaceId(),
                        queryVector.modelName(), queryVector.elapsedMillis()));
    }

    /**
     * Top-K 解析：null 用默认值，显式值必须在 1..maxTopK。
     */
    int resolveTopK(Integer topK) {
        int value = topK == null ? properties.getDefaultTopK() : topK;
        if (value < 1 || value > properties.getMaxTopK()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "topK 必须在 1.." + properties.getMaxTopK() + " 之间，实际传入 " + value,
                    "TOP_K_OUT_OF_RANGE");
        }
        return value;
    }

    private String filterDescription(RetrievalQueryRequest request) {
        List<String> parts = new ArrayList<>(3);
        if (request.documentId() != null && !request.documentId().isBlank()) {
            parts.add("documentId=" + request.documentId().strip());
        }
        if (request.version() != null && !request.version().isBlank()) {
            parts.add("version=" + request.version().strip());
        }
        if (request.strategy() != null && !request.strategy().isBlank()) {
            parts.add("strategy=" + request.strategy().strip());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}

package com.zl.d39.rag.service;

import com.zl.d39.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d39.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d39.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d39.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d39.rag.config.RagProperties;
import com.zl.d39.rag.dto.RagRequest;
import com.zl.d39.rag.model.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 检索通道：只做请求适配与结果转换，向量化与 Top-K 全部复用真实 pgvector 链路
 * {@link SimilarityRetrievalService}；本类不直接调用 ChatModel，也不持有 VectorStore。
 */
@Service
public class RagRetrievalService {

    private final SimilarityRetrievalService similarityRetrievalService;

    private final RagProperties properties;

    public RagRetrievalService(SimilarityRetrievalService similarityRetrievalService,
                               RagProperties properties) {
        this.similarityRetrievalService = similarityRetrievalService;
        this.properties = properties;
    }

    /**
     * 执行真实向量检索并转换为组装层数据对象；检索异常原样上抛，由编排器映射状态。
     */
    public List<RetrievedChunk> retrieve(RagRequest request) {
        Integer effectiveTopK = request.topK() != null ? request.topK() : properties.getTopK();
        RetrievalQueryRequest queryRequest = new RetrievalQueryRequest(
                request.query(), effectiveTopK,
                request.documentId(), request.version(), request.strategy());
        RetrievalResponse response = similarityRetrievalService.search(queryRequest);
        return response.hits().stream().map(this::toChunk).toList();
    }

    private RetrievedChunk toChunk(RetrievalHitView hit) {
        return new RetrievedChunk(
                hit.chunkId(), hit.documentId(), hit.version(), hit.chunkIndex(),
                hit.title(), hit.pageNumbers() == null ? List.of() : hit.pageNumbers(),
                hit.paragraphIndex(),
                hit.strategy(), hit.content(), hit.score(), hit.distance(),
                hit.lifecycleStatus(), hit.effectiveAt(), hit.expiresAt());
    }
}

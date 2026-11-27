package com.zl.d44.rerank;

import com.zl.d44.common.Result;
import com.zl.d44.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d44.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d44.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d44.knowledge.retrieval.service.SimilarityRetrievalService;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 重排只读验证端点（人测面；实验 Runner 不经本端点，直接组装候选调用端口）。
 * 权限：检索语义沿用 SimilarityRetrievalService 既有权限谓词；access.enabled 时端点处于
 * 既有 fail-closed 过滤器链内（D40 P3 端点门禁先例），仅 access-log 记录排除。
 */
@RestController
@RequestMapping("/api/v1/rerank")
@ConditionalOnProperty(prefix = "app.rerank", name = "enabled", havingValue = "true")
public class RerankController {

    private final SimilarityRetrievalService retrievalService;
    private final LocalLexicalReranker localReranker;
    private final ObjectProvider<BailianTextReranker> remoteRerankerProvider;

    public RerankController(SimilarityRetrievalService retrievalService,
                            LocalLexicalReranker localReranker,
                            ObjectProvider<BailianTextReranker> remoteRerankerProvider) {
        this.retrievalService = retrievalService;
        this.localReranker = localReranker;
        this.remoteRerankerProvider = remoteRerankerProvider;
    }

    @PostMapping("/search")
    public Result<RerankSearchResponse> search(@RequestBody RerankSearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return Result.fail(400, RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": query 空");
        }
        int candidateTopK = request.candidateTopK() == null ? 5 : request.candidateTopK();
        int topN = request.topN() == null ? 5 : request.topN();
        if (candidateTopK < 1 || candidateTopK > 50 || topN < 1 || topN > 50) {
            return Result.fail(400, RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": topK/topN 越界");
        }

        RerankPort port;
        if ("bailian".equalsIgnoreCase(request.provider() == null ? "local" : request.provider())) {
            port = remoteRerankerProvider.getIfAvailable();
            if (port == null) {
                return Result.fail(400, RerankReasonCode.RERANK_REMOTE_DISABLED.code());
            }
        } else if ("local".equalsIgnoreCase(request.provider() == null ? "local" : request.provider())) {
            port = localReranker;
        } else {
            return Result.fail(400, RerankReasonCode.RERANK_CONFIG_INVALID.code()
                    + ": provider=" + request.provider());
        }

        RetrievalResponse retrieval = retrievalService.search(new RetrievalQueryRequest(
                request.query(), candidateTopK, request.documentId(), null, null));
        List<RerankCandidate> candidates = new ArrayList<>();
        for (RetrievalHitView h : retrieval.hits()) {
            candidates.add(new RerankCandidate(h.chunkId(), h.documentId(),
                    h.content() == null ? "" : h.content(), h.rank(), h.score()));
        }
        if (candidates.isEmpty()) {
            return Result.success(new RerankSearchResponse(port.provider(),
                    LocalLexicalReranker.MODEL, 0, List.of(),
                    new RerankStageStats(0, 0, 0, null, port.provider(),
                            port == localReranker ? LocalLexicalReranker.MODEL : port.provider()),
                    RerankReasonCode.RERANK_EMPTY_CANDIDATES.code()));
        }
        int effTopN = Math.min(topN, candidates.size());
        RerankOutcome outcome;
        try {
            outcome = port.rerank(new RerankRequest("online", request.query(), candidates, effTopN));
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        }
        return Result.success(new RerankSearchResponse(port.provider(),
                outcome.stats().model(), candidates.size(), outcome.hits(),
                outcome.stats(), outcome.reasonCode()));
    }
}
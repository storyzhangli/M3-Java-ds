package com.zl.d42.rerank;

import java.util.List;

/**
 * 端口契约：候选 0..50；非空时 topN ∈ [1, candidates.size()]；空候选合法
 * （返回 RERANK_EMPTY_CANDIDATES，H4 空结果≠失败）。
 */
public record RerankRequest(String questionId, String query,
                            List<RerankCandidate> candidates, int topN) {

    public RerankRequest {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (query == null) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": query null");
        }
        if (candidates.size() > 50) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code()
                    + ": candidates=" + candidates.size() + " 超上限 50");
        }
        if (topN < 1 || topN > 50) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": topN=" + topN);
        }
        if (!candidates.isEmpty() && topN > candidates.size()) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code()
                    + ": topN=" + topN + " 超候选数 " + candidates.size());
        }
    }
}
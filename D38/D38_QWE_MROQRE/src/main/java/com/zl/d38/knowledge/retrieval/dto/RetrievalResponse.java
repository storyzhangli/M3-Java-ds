package com.zl.d38.knowledge.retrieval.dto;

import java.util.List;

/**
 * 一次检索的完整结果。
 *
 * @param query             原始查询文本
 * @param requestedTopK     调用方传入的 Top-K（null 表示用默认值）
 * @param effectiveTopK     实际生效的 Top-K
 * @param filterDescription 生效的过滤条件描述（无过滤时为 null）
 * @param hits              命中列表（按相似度降序）
 * @param hitCount          命中数量
 * @param elapsedMillis     端到端检索耗时（含查询向量化）
 * @param scoreSemantic     分数语义
 * @param distanceSemantic  距离语义
 * @param queryEmbedding    查询向量摘要（不含向量本体，避免响应体膨胀）
 */
public record RetrievalResponse(String query,
                                Integer requestedTopK,
                                int effectiveTopK,
                                String filterDescription,
                                List<RetrievalHitView> hits,
                                int hitCount,
                                long elapsedMillis,
                                String scoreSemantic,
                                String distanceSemantic,
                                QueryEmbeddingSummary queryEmbedding) {

    /**
     * 查询向量摘要：维度与向量空间用于证明"查询与入库处于同一向量空间"。
     */
    public record QueryEmbeddingSummary(int dimensions,
                                        String vectorSpaceId,
                                        String modelName,
                                        long elapsedMillis) {
    }
}

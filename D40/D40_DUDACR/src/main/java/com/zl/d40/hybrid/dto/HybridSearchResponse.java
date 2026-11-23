package com.zl.d40.hybrid.dto;

import com.zl.d40.hybrid.FusionTraceEntry;
import com.zl.d40.hybrid.keyword.KeywordMatchView;
import com.zl.d40.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d40.knowledge.retrieval.dto.RetrievalResponse;

import java.util.List;

/**
 * 混合检索响应（P2.3）。对齐既有 {@link RetrievalResponse} 字段语义，
 * <b>新增</b> keywordHits / fusionTrace / 分段计时 / 索引统计。
 *
 * <p>分数语义（§3.2 红线）：{@code hits[].score} 与 {@code scoreSemantic="RRF"}——
 * 禁止解释为余弦相似度；向量路原始余弦完整保留在 {@code vectorHits} 与 {@code fusionTrace}。</p>
 *
 * @param query            查询原文（规范化前）
 * @param requestedTopK    请求传入 topK（可为 null）
 * @param effectiveTopK    生效 topK（融合输出截断，冻结默认 5）
 * @param candidateTopN    每路候选数（冻结 10）
 * @param filterDescription 过滤描述（与既有链路同格式）
 * @param scoreSemantic    固定 "RRF"
 * @param hits             融合命中（rank 1 起；KEYWORD_ONLY 命中 distance=null）
 * @param total            融合输出条数
 * @param vectorHits       向量路原始命中（余弦语义，只读复用既有链路输出）
 * @param keywordHits      关键词路原始命中（BM25 语义）
 * @param fusionTrace      融合追溯（与 hits 等长同序）
 * @param vectorElapsedMillis 向量路耗时（既有 retrievalElapsedMillis 口径：含双重 Embedding）
 * @param keywordElapsedMillis 关键词路耗时
 * @param fusionElapsedMillis  融合耗时
 * @param totalElapsedMillis   端到端耗时
 * @param queryEmbedding   查询向量化摘要（透传向量路）
 * @param index            倒排索引统计（构建口径）
 */
public record HybridSearchResponse(String query,
                                   Integer requestedTopK,
                                   int effectiveTopK,
                                   int candidateTopN,
                                   String filterDescription,
                                   String scoreSemantic,
                                   List<RetrievalHitView> hits,
                                   int total,
                                   List<RetrievalHitView> vectorHits,
                                   List<KeywordMatchView> keywordHits,
                                   List<FusionTraceEntry> fusionTrace,
                                   long vectorElapsedMillis,
                                   long keywordElapsedMillis,
                                   long fusionElapsedMillis,
                                   long totalElapsedMillis,
                                   RetrievalResponse.QueryEmbeddingSummary queryEmbedding,
                                   IndexInfo index) {

    public HybridSearchResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
        vectorHits = vectorHits == null ? List.of() : List.copyOf(vectorHits);
        keywordHits = keywordHits == null ? List.of() : List.copyOf(keywordHits);
        fusionTrace = fusionTrace == null ? List.of() : List.copyOf(fusionTrace);
    }

    /**
     * 倒排索引统计视图。buildMillis 对应 keywordIndexMs 口径（一次性构建）；
     * heapDeltaBytes 为粗测口径（GC 噪声注明，H7）。
     */
    public record IndexInfo(int docCount,
                            long termCount,
                            long postingCount,
                            long contentUtf8Bytes,
                            long indexUtf8Bytes,
                            long buildMillis,
                            long heapDeltaBytes,
                            String fingerprint) {
    }
}

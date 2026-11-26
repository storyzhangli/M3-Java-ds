package com.zl.d43.hybrid;

/**
 * 单个 chunk 的融合追溯（P2.1，record 强约束，禁 Map）。
 *
 * <p>向量路原始余弦与关键词路 BM25 分在此完整保留——响应层 scoreSemantic="RRF"，
 * 严禁把 rrfScore 解释为相似度（§3.2 分数语义红线）。</p>
 *
 * @param chunkId            PG 主键 id
 * @param origin             来源：BOTH / VECTOR_ONLY / KEYWORD_ONLY
 * @param vectorRank         向量路名次（缺席为 null）
 * @param vectorSimilarity   向量路原始余弦相似度（缺席为 null）
 * @param vectorContribution 向量路 RRF 贡献 1/(k+rank)（缺席为 0.0）
 * @param keywordRank        关键词路名次（缺席为 null）
 * @param keywordBm25        关键词路 BM25 原始分（缺席为 null）
 * @param keywordContribution 关键词路 RRF 贡献（缺席为 0.0）
 * @param rrfScore           融合总分 = vectorContribution + keywordContribution
 *                           （固定求和顺序：先向量路、后关键词路）
 */
public record FusionTraceEntry(String chunkId,
                               String origin,
                               Integer vectorRank,
                               Double vectorSimilarity,
                               double vectorContribution,
                               Integer keywordRank,
                               Double keywordBm25,
                               double keywordContribution,
                               double rrfScore) {

    public static final String ORIGIN_BOTH = "BOTH";
    public static final String ORIGIN_VECTOR_ONLY = "VECTOR_ONLY";
    public static final String ORIGIN_KEYWORD_ONLY = "KEYWORD_ONLY";
}

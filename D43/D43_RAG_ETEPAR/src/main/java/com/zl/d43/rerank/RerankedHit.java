package com.zl.d43.rerank;

/**
 * 重排后命中：newRank 1..topN 连续；rerankScore 语义按适配器（本地=lexical 覆盖率，远程=relevance_score）。
 */
public record RerankedHit(String chunkId, int newRank, int originalRank,
                          double rerankScore, Double cosineScore) {
}
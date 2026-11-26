package com.zl.d43.rerank;

import com.zl.d43.rerank.RerankStageStats;
import com.zl.d43.rerank.RerankedHit;

import java.util.List;

/** 只读验证面响应（结构化，无无约束 Map；零 Key 零敏感字段）。 */
public record RerankSearchResponse(String provider,
                                   String model,
                                   int candidateCount,
                                   List<RerankedHit> hits,
                                   RerankStageStats stats,
                                   String reasonCode) {
}
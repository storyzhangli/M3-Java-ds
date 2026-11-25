package com.zl.d42.rerank.experiment;

/** 单组门禁判定（verdict：ADOPTED_OPTIONAL / NOT_ADOPTED / NOT_ADOPTED_PENDING_COST / BLOCKED）。 */
public record RerankDecisionRecord(String group,
                                   String verdict,
                                   boolean gateA,
                                   boolean gateB,
                                   boolean gateC,
                                   boolean gateE,
                                   double deltaMrrAtK,
                                   double deltaHitRateAtK,
                                   double p95DeltaMs,
                                   String basis) {
}
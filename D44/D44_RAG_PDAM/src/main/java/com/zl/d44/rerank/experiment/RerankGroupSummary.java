package com.zl.d44.rerank.experiment;

import com.zl.d44.rerank.experiment.RerankExperimentGroup;

/** 单组聚合指标（G-B/G-C/G-E 判定输入；BLOCKED 组指标字段置 0 并记录原因）。 */
public record RerankGroupSummary(String group,
                                 String status,
                                 int questionCount,
                                 int runCount,
                                 double mrrAtK,
                                 double hitRateAtK,
                                 double averageNoise,
                                 double p50TotalMs,
                                 double p95TotalMs,
                                 double p95RerankMs,
                                 int failCount,
                                 boolean repeatDivergenceFree,
                                 String blockedReason) {

    public static RerankGroupSummary blocked(RerankExperimentGroup group, String reason) {
        return new RerankGroupSummary(group.name(), "BLOCKED", 0, 0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, true, reason);
    }
}
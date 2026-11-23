package com.zl.d40.experiment;

import com.zl.d40.experiment.ExperimentReasonCode;

import java.util.List;

/**
 * 单题相关性判题结果（纯逻辑，不依赖任何外部服务）。
 *
 * @param questionId         问题编号
 * @param expectedLogicalIds Gold 标注的逻辑片段 ID
 * @param returnedLogicalIds 实际返回并映射后的逻辑片段 ID（按 rank、去重）
 * @param hit                是否至少命中一个标注片段
 * @param relevantCount      相关返回片段数
 * @param returnedCount      实际返回片段数
 * @param noiseCount         无关返回片段数
 * @param reasonCode         零命中时为 ZERO_HITS_AFTER_THRESHOLD，否则为 null
 */
public record RelevanceJudgment(String questionId,
                                List<String> expectedLogicalIds,
                                List<String> returnedLogicalIds,
                                boolean hit,
                                int relevantCount,
                                int returnedCount,
                                int noiseCount,
                                String reasonCode) {

    /**
     * 依据 Gold 标注对单题判题。
     *
     * @param questionId         问题编号
     * @param expectedLogicalIds 标注逻辑 ID（单标签题仅一个）
     * @param returnedLogicalIds 返回逻辑 ID（去重、按 rank）
     */
    public static RelevanceJudgment judge(String questionId,
                                          List<String> expectedLogicalIds,
                                          List<String> returnedLogicalIds) {
        List<String> expected = List.copyOf(expectedLogicalIds);
        List<String> returned = List.copyOf(returnedLogicalIds);

        int relevant = 0;
        int noise = 0;
        for (String id : returned) {
            if (expected.contains(id)) {
                relevant++;
            } else {
                noise++;
            }
        }
        boolean hit = relevant >= 1;
        String reasonCode = returned.isEmpty()
                ? ExperimentReasonCode.ZERO_HITS_AFTER_THRESHOLD.code() : null;
        return new RelevanceJudgment(questionId, expected, returned, hit,
                relevant, returned.size(), noise, reasonCode);
    }

    /**
     * 单题 Precision@K：相关返回数 / 实际返回数；零返回时为 0。
     */
    public double precision() {
        if (returnedCount == 0) {
            return 0.0;
        }
        return (double) relevantCount / (double) returnedCount;
    }

    /**
     * 单题 Recall：命中的标注数 / 标注总数；单标签题即 0/1。
     */
    public double recall() {
        if (expectedLogicalIds.isEmpty()) {
            return 0.0;
        }
        long hitExpected = expectedLogicalIds.stream().filter(returnedLogicalIds::contains).count();
        return (double) hitExpected / (double) expectedLogicalIds.size();
    }
}

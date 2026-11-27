package com.zl.d44.experiment;

import com.zl.d44.experiment.RetrievalExperimentParameters;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单组实验汇总。
 *
 * @param groupId                实验组编号
 * @param parameters             组参数
 * @param indexedChunkCount      本组实际入库片段数
 * @param evaluableQuestionCount 可判题数
 * @param hitQuestionCount       命中题数
 * @param hitRate                HitRate@K
 * @param microPrecision         micro Precision@K
 * @param averageRecall          平均 Recall
 * @param averageNoise           平均噪声片段数
 * @param latency                延迟统计
 * @param zeroHitQuestionIds     零命中问题
 * @param divergedQuestionIds    重复结果不一致问题
 * @param reasonCodes            出现过的非空原因码（去重、有序）
 */
public record GroupExperimentSummary(String groupId,
                                     RetrievalExperimentParameters parameters,
                                     int indexedChunkCount,
                                     int evaluableQuestionCount,
                                     int hitQuestionCount,
                                     double hitRate,
                                     double microPrecision,
                                     double averageRecall,
                                     double averageNoise,
                                     LatencyStats latency,
                                     List<String> zeroHitQuestionIds,
                                     List<String> divergedQuestionIds,
                                     List<String> reasonCodes) {

    public static GroupExperimentSummary build(ExperimentGroup group,
                                               RetrievalExperimentParameters parameters,
                                               int indexedChunkCount,
                                               List<QuestionExperimentRecord> questions,
                                               List<Double> allLatencies) {
        List<RelevanceJudgment> judgments = questions.stream()
                .map(question -> new RelevanceJudgment(
                        question.questionId(),
                        question.expectedLogicalIds(),
                        QuestionExperimentRecord.collectLogicalIds(
                                question.runs().get(0)),
                        question.hit(),
                        question.relevantCount(),
                        question.returnedCount(),
                        question.noiseCount(),
                        question.reasonCode()))
                .toList();

        List<String> zeroHit = questions.stream()
                .filter(question -> question.returnedCount() == 0)
                .map(QuestionExperimentRecord::questionId)
                .toList();
        List<String> diverged = questions.stream()
                .filter(question -> !question.repeatConsistent())
                .map(QuestionExperimentRecord::questionId)
                .toList();

        Set<String> reasonCodes = new LinkedHashSet<>();
        questions.stream()
                .map(QuestionExperimentRecord::reasonCode)
                .filter(code -> code != null)
                .forEach(reasonCodes::add);
        if (!diverged.isEmpty()) {
            reasonCodes.add(ExperimentReasonCode.REPEAT_RESULT_DIVERGED.code());
        }

        long hits = questions.stream().filter(QuestionExperimentRecord::hit).count();
        return new GroupExperimentSummary(
                group.name(),
                parameters,
                indexedChunkCount,
                questions.size(),
                (int) hits,
                ExperimentMetricsCalculator.hitRate(judgments),
                ExperimentMetricsCalculator.microPrecision(judgments),
                ExperimentMetricsCalculator.averageRecall(judgments),
                ExperimentMetricsCalculator.averageNoise(judgments),
                ExperimentMetricsCalculator.latencyStats(allLatencies),
                zeroHit,
                diverged,
                new ArrayList<>(reasonCodes));
    }
}

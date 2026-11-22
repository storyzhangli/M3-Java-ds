package com.zl.d39.experiment;

import com.zl.d39.experiment.ExperimentQuestion;
import com.zl.d39.experiment.RelevanceJudgment;
import com.zl.d39.experiment.ReturnedHitRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 单题完整实验记录（含全部重复运行与判题结论）。
 *
 * @param questionId        问题编号
 * @param question          问题原文
 * @param category          问题类别
 * @param expectedLogicalIds 标注逻辑片段 ID
 * @param runs              重复运行记录
 * @param hit               是否命中（基于首次运行判题）
 * @param relevantCount     相关返回数
 * @param returnedCount     实际返回数
 * @param noiseCount        无关返回数
 * @param precision         单题 Precision
 * @param recall            单题 Recall
 * @param repeatConsistent  各次运行返回逻辑 ID 是否一致
 * @param reasonCode        零命中原因码，否则为 null
 */
public record QuestionExperimentRecord(String questionId,
                                       String question,
                                       String category,
                                       List<String> expectedLogicalIds,
                                       List<QuestionRunRecord> runs,
                                       boolean hit,
                                       int relevantCount,
                                       int returnedCount,
                                       int noiseCount,
                                       double precision,
                                       double recall,
                                       boolean repeatConsistent,
                                       String reasonCode) {

    public static QuestionExperimentRecord build(ExperimentQuestion question,
                                                 List<QuestionRunRecord> runs) {
        List<String> firstReturned = collectLogicalIds(runs.get(0));
        RelevanceJudgment judgment = RelevanceJudgment.judge(
                question.id(), question.expectedChunkIds(), firstReturned);

        List<List<String>> allReturned = runs.stream()
                .map(QuestionExperimentRecord::collectLogicalIds)
                .toList();
        boolean repeatConsistent = allReturned.stream()
                .allMatch(ids -> ids.equals(firstReturned));

        return new QuestionExperimentRecord(
                question.id(),
                question.question(),
                question.category(),
                List.copyOf(question.expectedChunkIds()),
                List.copyOf(runs),
                judgment.hit(),
                judgment.relevantCount(),
                judgment.returnedCount(),
                judgment.noiseCount(),
                judgment.precision(),
                judgment.recall(),
                repeatConsistent,
                judgment.reasonCode());
    }

    /**
     * 从一次运行中按 rank 收集去重后的逻辑片段 ID。
     */
    static List<String> collectLogicalIds(QuestionRunRecord run) {
        List<String> ids = new ArrayList<>();
        for (ReturnedHitRecord hit : run.hits()) {
            for (String logicalId : hit.logicalChunkIds()) {
                if (!ids.contains(logicalId)) {
                    ids.add(logicalId);
                }
            }
        }
        return List.copyOf(ids);
    }
}

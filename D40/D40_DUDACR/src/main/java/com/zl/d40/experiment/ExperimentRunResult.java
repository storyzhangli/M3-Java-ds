package com.zl.d40.experiment;

import com.zl.d40.experiment.ChunkIdMappingRecord;
import com.zl.d40.experiment.GroupExperimentSummary;
import com.zl.d40.experiment.QuestionExperimentRecord;
import com.zl.d40.experiment.RetrievalExperimentMatrix;

import java.util.List;

/**
 * 一次完整实验（五组）的运行结果容器。
 *
 * @param matrix 实验矩阵
 * @param groups 各组运行结果（有序）
 */
public record ExperimentRunResult(RetrievalExperimentMatrix matrix,
                                  List<GroupRunResult> groups) {

    /**
     * 单组运行结果。
     *
     * @param summary         组汇总
     * @param questionRecords 逐题记录
     * @param mapping         逻辑 ID 与向量 ID 映射表
     */
    public record GroupRunResult(GroupExperimentSummary summary,
                                 List<QuestionExperimentRecord> questionRecords,
                                 ChunkIdMappingRecord mapping) {
    }
}

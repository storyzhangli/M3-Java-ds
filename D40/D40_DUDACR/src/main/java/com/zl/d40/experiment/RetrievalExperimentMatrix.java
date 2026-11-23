package com.zl.d40.experiment;

import com.zl.d40.experiment.ExperimentGroup;
import com.zl.d40.experiment.ExperimentReasonCode;
import com.zl.d40.experiment.RetrievalExperimentParameters;

import java.util.List;

/**
 * 检索参数对照实验矩阵（修复版锁死，无 G5 可选组）。
 *
 * <p>固定项在所有组之间保持一致：同一问题集、同一语料、同一 Embedding 模型、
 * 同一距离度量、同一切分策略，仅矩阵声明的单一变量变化。</p>
 *
 * @param strategy          切分策略
 * @param lengthUnit        长度单位（码点，非 Token）
 * @param warmupRuns        每组预热次数（不计入统计）
 * @param repeatRuns        每题重复次数（计入统计）
 * @param corpusResource    语料 classpath 资源
 * @param questionResource  问题集文件（相对仓库根）
 * @param questionCount     纳入实验的问题数
 * @param embeddingModel    Embedding 模型标识
 * @param distanceMetric    距离度量
 * @param dimensions        向量维度
 * @param groups            五组参数（有序）
 */
public record RetrievalExperimentMatrix(String strategy,
                                        String lengthUnit,
                                        int warmupRuns,
                                        int repeatRuns,
                                        String corpusResource,
                                        String questionResource,
                                        int questionCount,
                                        String embeddingModel,
                                        String distanceMetric,
                                        int dimensions,
                                        List<RetrievalExperimentParameters> groups) {

    /**
     * 构建默认矩阵（修复版固定口径）。
     */
    public static RetrievalExperimentMatrix defaultMatrix() {
        List<RetrievalExperimentParameters> groups = java.util.Arrays.stream(ExperimentGroup.values())
                .map(ExperimentGroup::toParameters)
                .toList();
        return new RetrievalExperimentMatrix(
                "FIXED_LENGTH",
                "CODE_POINT",
                1,
                3,
                "knowledge/equipment-fault-corpus.json",
                "docs/evidence/D25/rag-design/questions.jsonl",
                10,
                "local-hash-ngram-v1",
                "COSINE",
                384,
                groups);
    }

    public RetrievalExperimentParameters group(String groupId) {
        return groups.stream()
                .filter(group -> group.groupId().equals(groupId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        ExperimentReasonCode.CONFIG_INVALID.code() + ": 未知实验组 " + groupId));
    }
}

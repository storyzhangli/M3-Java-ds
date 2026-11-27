package com.zl.d44.experiment;

import com.zl.d44.experiment.RetrievalExperimentParameters;

/**
 * 实验矩阵中的五个实验组（一个真实基线 + 四个单变量组）。
 *
 * <p>除 G0 基线外，每个组相对基线只改变一个变量，保证差异可归因。</p>
 */
public enum ExperimentGroup {

    G0(480, 60, 5, 0.00, "基线（骨架真实配置）"),
    G1(200, 60, 5, 0.00, "只改 chunk size（480→200）"),
    G2(480, 20, 5, 0.00, "只改 overlap（60→20）"),
    G3(480, 60, 10, 0.00, "只改 Top-K（5→10）"),
    G4(480, 60, 5, 0.25, "只改阈值（0.00→0.25）");

    private final int chunkSize;
    private final int overlap;
    private final int topK;
    private final double similarityThreshold;
    private final String changedVariable;

    ExperimentGroup(int chunkSize, int overlap, int topK,
                    double similarityThreshold, String changedVariable) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
        this.changedVariable = changedVariable;
    }

    public RetrievalExperimentParameters toParameters() {
        return new RetrievalExperimentParameters(name(), chunkSize, overlap, topK,
                similarityThreshold, changedVariable);
    }

    /**
     * 本组实验数据的 documentId 前缀（用于分组隔离，只追加、不删除）。
     */
    public String documentIdPrefix() {
        return "d44exp-" + name().toLowerCase() + "-";
    }

    /**
     * 本组实验版本号（写入向量 metadata，参与稳定 ID 计算）。
     */
    public String experimentVersion() {
        return "exp-" + name().toLowerCase() + "-v1";
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }

    public int topK() {
        return topK;
    }

    public double similarityThreshold() {
        return similarityThreshold;
    }

    public String changedVariable() {
        return changedVariable;
    }
}

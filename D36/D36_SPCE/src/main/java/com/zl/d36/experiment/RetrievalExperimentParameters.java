package com.zl.d36.experiment;

import com.zl.d36.knowledge.chunking.ChunkingProperties;
import com.zl.d36.knowledge.retrieval.config.RetrievalProperties;

/**
 * 单组实验的不可变参数。
 *
 * <p>实验参数作为独立对象传递，不修改 Spring 全局配置、不动态刷新 Bean；
 * 长度单位为 Unicode 码点，不是 Token。</p>
 *
 * @param groupId             实验组编号
 * @param chunkSize           切分长度（码点）
 * @param overlap             重叠长度（码点）
 * @param topK                Top-K
 * @param similarityThreshold 余弦相似度下限（0.0 表示接受全部）
 * @param changedVariable     本组相对基线唯一改变的变量说明
 */
public record RetrievalExperimentParameters(String groupId,
                                            int chunkSize,
                                            int overlap,
                                            int topK,
                                            double similarityThreshold,
                                            String changedVariable) {

    /**
     * 参数合法性自检；非法组合直接失败，不进入实验链路。
     */
    public void requireValid() {
        if (groupId == null || groupId.isBlank()) {
            throw invalid("groupId 不能为空");
        }
        if (chunkSize < 1) {
            throw invalid("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw invalid("overlap 不能为负");
        }
        if (overlap >= chunkSize) {
            throw invalid("overlap 必须小于 chunkSize");
        }
        if (topK < 1) {
            throw invalid("topK 必须大于 0");
        }
        if (Double.isNaN(similarityThreshold)
                || similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw invalid("similarityThreshold 必须是 0.0..1.0 的余弦相似度下限");
        }
    }

    /**
     * 构造仅用于本组的独立切分配置；不触碰生产配置 Bean。
     */
    public ChunkingProperties toChunkingProperties() {
        requireValid();
        ChunkingProperties props = new ChunkingProperties();
        props.setChunkSize(chunkSize);
        props.setOverlap(overlap);
        // 沿用骨架真实边界，保证与生产校验口径一致。
        props.setMinChunkSize(80);
        props.setMaxChunkSize(720);
        props.requireValid();
        return props;
    }

    /**
     * 构造仅用于本组的独立检索配置；不触碰生产配置 Bean。
     */
    public RetrievalProperties toRetrievalProperties() {
        requireValid();
        RetrievalProperties props = new RetrievalProperties();
        props.setDefaultTopK(topK);
        props.setMaxTopK(50);
        props.setSimilarityThreshold(similarityThreshold);
        props.validate();
        return props;
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(
                ExperimentReasonCode.CONFIG_INVALID.code() + ": " + message);
    }
}

package com.zl.d40.experiment;

/**
 * 实验专用稳定原因码（集中定义）。
 *
 * <p>未知原因码一律不得吞掉：出现未登记的失败情形时，应作为失败暴露并补充登记，
 * 而不是静默忽略。</p>
 */
public enum ExperimentReasonCode {

    CONFIG_INVALID("矩阵参数非法，或组内同时漂移多个变量"),
    CHUNKING_FAILED("重新切分执行失败"),
    EMBEDDING_FAILED("向量化失败或返回非法向量"),
    INDEX_FAILED("分组入库失败"),
    SEARCH_FAILED("向量检索执行失败"),
    ID_MAPPING_AMBIGUOUS("逻辑 ID 与向量 ID 映射缺失或一对多歧义"),
    ZERO_HITS_AFTER_THRESHOLD("阈值过滤后零命中（真实结果，非异常）"),
    K_EXCEEDS_INDEXED_CHUNKS("Top-K 超过本组实际入库片段数"),
    REPEAT_RESULT_DIVERGED("重复执行命中结果不一致"),
    GROUP_ISOLATION_CONFLICT("检测到跨组数据污染或命名空间冲突"),
    RESULT_WRITE_FAILED("证据文件写出失败"),
    MEASUREMENT_TIMEOUT("计时或执行超时");

    private final String description;

    ExperimentReasonCode(String description) {
        this.description = description;
    }

    public String code() {
        return name();
    }

    public String description() {
        return description;
    }
}

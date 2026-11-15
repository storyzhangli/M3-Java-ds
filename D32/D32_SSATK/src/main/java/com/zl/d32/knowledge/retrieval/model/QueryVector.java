package com.zl.d32.knowledge.retrieval.model;

/**
 * 一次查询向量化的真实结果。
 *
 * <p>{@code values} 为模型原始输出（384 维 float），构造与读取都做防御性拷贝，
 * 避免调用方修改内部数组；{@code vectorSpaceId} 用于与入库向量空间比对，
 * 只有同一模型/版本/维度的查询向量才允许检索。</p>
 *
 * @param query         归一化后的查询文本（去首尾空白）
 * @param values        查询向量
 * @param dimensions    维度
 * @param vectorSpaceId 向量空间标识（D31 入库时写入 metadata 的同一值）
 * @param modelName     模型名
 * @param elapsedMillis 向量化耗时（毫秒）
 */
public record QueryVector(String query,
                          float[] values,
                          int dimensions,
                          String vectorSpaceId,
                          String modelName,
                          long elapsedMillis) {

    public QueryVector {
        values = values == null ? new float[0] : values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }
}

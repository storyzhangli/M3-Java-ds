package com.zl.d40.knowledge.retrieval.support;

import com.zl.d40.common.ResultCode;
import com.zl.d40.knowledge.retrieval.RetrievalException;

/**
 * 查询向量合法性校验（纯函数，无 Spring 依赖，便于单元测试）。
 *
 * <p>校验项与失败原因一一对应：空向量、维度不一致、NaN/Infinity、全零向量。
 * 这些情况一旦进入 pgvector 查询，要么 SQL 报错、要么返回不可解释的分数，
 * 因此必须在调用 VectorStore 之前拦截。</p>
 */
public final class QueryVectorValidator {

    private QueryVectorValidator() {
    }

    /**
     * @param vector           查询向量
     * @param expectedDimensions 期望维度（与入库向量一致，固定 384）
     */
    public static void validate(float[] vector, int expectedDimensions) {
        if (vector == null || vector.length == 0) {
            throw new RetrievalException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "查询向量为空，拒绝检索", "QUERY_VECTOR_EMPTY");
        }
        if (vector.length != expectedDimensions) {
            throw new RetrievalException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "查询向量维度 " + vector.length + " 与期望维度 " + expectedDimensions
                            + " 不一致，拒绝检索", "QUERY_VECTOR_DIMENSION_MISMATCH");
        }
        double sumSquares = 0.0d;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new RetrievalException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                        "查询向量包含 NaN 或 Infinity，拒绝检索", "QUERY_VECTOR_NOT_FINITE");
            }
            sumSquares += (double) value * value;
        }
        if (sumSquares <= 0.0d) {
            throw new RetrievalException(ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "查询向量全为零向量，无法计算余弦相似度", "QUERY_VECTOR_ALL_ZERO");
        }
    }
}

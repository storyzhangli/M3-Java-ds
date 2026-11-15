package com.zl.d32.knowledge.retrieval;

import com.zl.d32.knowledge.retrieval.support.QueryVectorValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询向量校验：维度、NaN/Infinity、全零向量都必须在进入 pgvector 前拦截。
 */
class QueryVectorValidatorTest {

    private static final int DIMENSIONS = 384;

    @Test
    @DisplayName("合法 384 维非零向量通过")
    void acceptsValidVector() {
        float[] vector = new float[DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) ((i % 7) - 3) / 10.0f;
        }
        assertThatCode(() -> QueryVectorValidator.validate(vector, DIMENSIONS))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 向量拒绝，errorCode=QUERY_VECTOR_EMPTY")
    void rejectsNullVector() {
        assertThatThrownBy(() -> QueryVectorValidator.validate(null, DIMENSIONS))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((RetrievalException) ex).getErrorCode()).isEqualTo("QUERY_VECTOR_EMPTY"));
    }

    @Test
    @DisplayName("空数组拒绝")
    void rejectsEmptyVector() {
        assertThatThrownBy(() -> QueryVectorValidator.validate(new float[0], DIMENSIONS))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("查询向量为空");
    }

    @Test
    @DisplayName("维度不一致拒绝，错误信息包含实际与期望维度")
    void rejectsDimensionMismatch() {
        float[] vector = new float[DIMENSIONS - 1];
        vector[0] = 1.0f;
        assertThatThrownBy(() -> QueryVectorValidator.validate(vector, DIMENSIONS))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining(String.valueOf(DIMENSIONS - 1))
                .hasMessageContaining(String.valueOf(DIMENSIONS));
    }

    @Test
    @DisplayName("包含 NaN 拒绝，errorCode=QUERY_VECTOR_NOT_FINITE")
    void rejectsNaN() {
        float[] vector = new float[DIMENSIONS];
        vector[0] = 1.0f;
        vector[10] = Float.NaN;
        assertThatThrownBy(() -> QueryVectorValidator.validate(vector, DIMENSIONS))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((RetrievalException) ex).getErrorCode())
                        .isEqualTo("QUERY_VECTOR_NOT_FINITE"));
    }

    @Test
    @DisplayName("包含 Infinity 拒绝")
    void rejectsInfinity() {
        float[] vector = new float[DIMENSIONS];
        vector[0] = Float.POSITIVE_INFINITY;
        assertThatThrownBy(() -> QueryVectorValidator.validate(vector, DIMENSIONS))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("Infinity");
    }

    @Test
    @DisplayName("全零向量拒绝，errorCode=QUERY_VECTOR_ALL_ZERO")
    void rejectsAllZeroVector() {
        assertThatThrownBy(() -> QueryVectorValidator.validate(new float[DIMENSIONS], DIMENSIONS))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((RetrievalException) ex).getErrorCode()).isEqualTo("QUERY_VECTOR_ALL_ZERO"));
    }
}

package com.zl.d32.knowledge.retrieval;

import com.zl.d32.knowledge.retrieval.config.RetrievalProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索配置：默认值必须与 D32 验收口径一致（Top-K=5），非法组合必须 fail-fast。
 */
class RetrievalPropertiesTest {

    @Test
    @DisplayName("默认值：Top-K=5，上限 50，查询长度 2000，相似度下限 0.0")
    void defaultsMatchAcceptanceContract() {
        RetrievalProperties properties = new RetrievalProperties();
        assertThat(properties.getDefaultTopK()).isEqualTo(5);
        assertThat(properties.getMaxTopK()).isEqualTo(50);
        assertThat(properties.getMaxQueryLength()).isEqualTo(2000);
        assertThat(properties.getSimilarityThreshold()).isZero();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("default-top-k 小于 1 时拒绝")
    void rejectsNonPositiveDefaultTopK() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setDefaultTopK(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-top-k");
    }

    @Test
    @DisplayName("default-top-k 大于 max-top-k 时拒绝")
    void rejectsDefaultTopKAboveMax() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setDefaultTopK(51);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-top-k");
    }

    @Test
    @DisplayName("max-top-k 越界（0 与 1001）时拒绝")
    void rejectsInvalidMaxTopK() {
        RetrievalProperties zero = new RetrievalProperties();
        zero.setDefaultTopK(1);
        zero.setMaxTopK(0);
        assertThatThrownBy(zero::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-top-k");

        RetrievalProperties tooLarge = new RetrievalProperties();
        tooLarge.setMaxTopK(1001);
        assertThatThrownBy(tooLarge::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-top-k");
    }

    @Test
    @DisplayName("查询长度上限越界时拒绝")
    void rejectsInvalidMaxQueryLength() {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setMaxQueryLength(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-query-length");
    }

    @Test
    @DisplayName("相似度下限超出 0..1 或为 NaN 时拒绝")
    void rejectsInvalidSimilarityThreshold() {
        RetrievalProperties negative = new RetrievalProperties();
        negative.setSimilarityThreshold(-0.1);
        assertThatThrownBy(negative::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("similarity-threshold");

        RetrievalProperties aboveOne = new RetrievalProperties();
        aboveOne.setSimilarityThreshold(1.1);
        assertThatThrownBy(aboveOne::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("similarity-threshold");

        RetrievalProperties nan = new RetrievalProperties();
        nan.setSimilarityThreshold(Double.NaN);
        assertThatThrownBy(nan::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("similarity-threshold");
    }
}

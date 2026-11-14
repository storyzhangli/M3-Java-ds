package com.zl.d31.vectorindex;

import com.zl.d31.knowledge.vectorindex.config.VectorIndexProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 向量索引配置校验单元测试：非法值必须 fail-fast，而不是带病启动。
 */
class VectorIndexPropertiesTest {

    @Test
    void defaultsAreValid() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.validate();
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getSplitVersion()).isEqualTo("split-v1");
        assertThat(properties.isReconcileEnabled()).isFalse();
    }

    @Test
    void nonPositiveBatchSizeIsRejected() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setBatchSize(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void maxAttemptsOutOfRangeIsRejected() {
        VectorIndexProperties low = new VectorIndexProperties();
        low.setMaxAttempts(0);
        assertThatThrownBy(low::validate).isInstanceOf(IllegalArgumentException.class);

        VectorIndexProperties high = new VectorIndexProperties();
        high.setMaxAttempts(21);
        assertThatThrownBy(high::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveStuckMinutesIsRejected() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setStuckMinutes(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeBackoffIsRejected() {
        VectorIndexProperties properties = new VectorIndexProperties();
        properties.setRetryBackoffMs(-1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankIdentityFieldsAreRejected() {
        VectorIndexProperties split = new VectorIndexProperties();
        split.setSplitVersion("  ");
        assertThatThrownBy(split::validate).isInstanceOf(IllegalArgumentException.class);

        VectorIndexProperties strategy = new VectorIndexProperties();
        strategy.setDefaultStrategy("");
        assertThatThrownBy(strategy::validate).isInstanceOf(IllegalArgumentException.class);

        VectorIndexProperties cron = new VectorIndexProperties();
        cron.setReconcileCron("");
        assertThatThrownBy(cron::validate).isInstanceOf(IllegalArgumentException.class);
    }
}

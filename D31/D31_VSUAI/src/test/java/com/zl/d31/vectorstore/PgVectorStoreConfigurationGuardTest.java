package com.zl.d31.vectorstore;

import com.zl.d31.common.AiConfigurationException;
import com.zl.d31.knowledge.embedding.DistanceMetric;
import com.zl.d31.knowledge.vectorstore.PgVectorStoreConfiguration;
import com.zl.d31.knowledge.vectorstore.PgVectorStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PgVectorStoreConfiguration 装配前置守卫测试：
 * 维度/距离度量不一致必须在触碰数据库之前拒绝装配。
 */
class PgVectorStoreConfigurationGuardTest {

    private PgVectorStoreProperties cosyProps(int dimensions) {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setUrl("jdbc:postgresql://127.0.0.1:5432/spring_ai");
        properties.setUsername("postgres");
        properties.setDimensions(dimensions);
        properties.setDistanceMetric(DistanceMetric.COSINE);
        return properties;
    }

    @Test
    @DisplayName("配置维度 384 与 EmbeddingModel 维度 512 不一致时拒绝装配")
    void rejectsDimensionMismatchBeforeTouchingDatabase() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(512);

        PgVectorStoreConfiguration configuration = new PgVectorStoreConfiguration();

        assertThatThrownBy(() -> configuration.pgVectorStore(cosyProps(384), embeddingModel))
                .isInstanceOf(AiConfigurationException.class)
                .hasMessageContaining("维度")
                .hasMessageContaining("384")
                .hasMessageContaining("512");
    }

    @Test
    @DisplayName("非 COSINE 距离度量被拒绝：D31 仅支持 vector_cosine_ops")
    void rejectsNonCosineDistance() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(384);

        PgVectorStoreProperties properties = cosyProps(384);
        properties.setDistanceMetric(DistanceMetric.EUCLIDEAN);

        PgVectorStoreConfiguration configuration = new PgVectorStoreConfiguration();

        assertThatThrownBy(() -> configuration.pgVectorStore(properties, embeddingModel))
                .isInstanceOf(AiConfigurationException.class)
                .hasMessageContaining("COSINE");
    }
}
